package com.commercecore.checkout;

import com.commercecore.outbox.AggregateType;
import com.commercecore.outbox.OutboxEventType;
import com.commercecore.outbox.OutboxEventWriter;
import com.commercecore.payment.Payment;
import com.commercecore.payment.PaymentReconciliationCaseRepository;
import com.commercecore.payment.PaymentRepository;
import com.commercecore.payment.PaymentStatus;
import com.commercecore.payment.ReconciliationReason;
import com.commercecore.payment.ReconciliationStatus;
import com.commercecore.reservation.InventoryReservation;
import com.commercecore.reservation.ReservationService;
import com.commercecore.reservation.ReservationStatus;
import com.commercecore.shared.BusinessRuleViolation;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a committed payment outcome (AUTHORIZED/FAILED) to the order and its reservations, in
 * one atomic transaction that also claims this event's workflow receipt and writes the resulting
 * {@code ORDER_CONFIRMED}/{@code ORDER_CANCELLED} outbox event. Called from
 * {@link OrderPaymentWorkflowConsumer}, which is deliberately thin — everything that decides
 * correctness lives here, not in the Kafka listener.
 *
 * <p>Every method here is the single business transaction for one delivery attempt of one
 * payment event. {@code @Transactional} covers the receipt claim, the payment/order/reservation
 * reads and writes, and the outbox insert together: if any invariant below is violated, the whole
 * method throws and every part of that sequence — including the receipt row — rolls back
 * together. Nothing here is ever partially committed.
 */
@Service
public class OrderPaymentWorkflowService {

    private final OrderWorkflowEventReceiptRepository receiptRepository;
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final ReservationService reservationService;
    private final OutboxEventWriter outboxEventWriter;
    private final PaymentReconciliationCaseRepository reconciliationCaseRepository;

    public OrderPaymentWorkflowService(OrderWorkflowEventReceiptRepository receiptRepository,
        PaymentRepository paymentRepository, OrderRepository orderRepository, ReservationService reservationService,
        OutboxEventWriter outboxEventWriter, PaymentReconciliationCaseRepository reconciliationCaseRepository) {
        this.receiptRepository = receiptRepository;
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.reservationService = reservationService;
        this.outboxEventWriter = outboxEventWriter;
        this.reconciliationCaseRepository = reconciliationCaseRepository;
    }

    /**
     * PENDING -&gt; CONFIRMED, and every ACTIVE reservation for the order -&gt; CONFIRMED.
     * Inventory is never touched here: {@code available_quantity} was already decremented when
     * each reservation became ACTIVE (at checkout time) — confirming only makes that decrement
     * permanent, it does not decrement again. CONFIRMED order/CONFIRMED-already reservations are
     * an idempotent no-op (a second, different event ID reporting the same already-applied
     * outcome — see {@code docs/order-workflow.md}). A RELEASED or EXPIRED reservation means
     * CommerceCore no longer owns that stock; confirming anyway would risk overselling, so this
     * is refused as a conflict and requires future reconciliation, not invented here.
     */
    @Transactional
    public void handlePaymentAuthorized(UUID eventId, UUID paymentId, UUID orderId) {
        int inserted = claimReceipt(eventId, OutboxEventType.PAYMENT_AUTHORIZED, paymentId, orderId);
        if (inserted == 0) {
            return;
        }

        Payment payment = requirePayment(paymentId, orderId);
        if (payment.getStatus() != PaymentStatus.AUTHORIZED) {
            throw workflowConflict("payment_state_mismatch", "Payment " + paymentId
                + " is not AUTHORIZED in PostgreSQL; refusing to trust the Kafka event payload alone");
        }

        Order order = requireOrderLocked(orderId);
        switch (order.getStatus()) {
            case CONFIRMED -> {
                return; // idempotent no-op: this outcome was already applied by an earlier event
            }
            case CANCELLED -> throw workflowConflict("conflicting_order_transition",
                "Order " + orderId + " is already CANCELLED; refusing to apply AUTHORIZED");
            case PENDING -> {
                // proceed below
            }
        }

        if (!reservationService.isOwnershipIntactForOrder(orderId)) {
            // A permanent business inconsistency, not a transient failure: no amount of Kafka
            // redelivery can recreate stock CommerceCore no longer owns. Committing durable
            // REQUIRES_REVIEW evidence (and this event's receipt) and acknowledging — rather than
            // throwing and retrying forever — is the deliberate policy here; see
            // docs/reconciliation.md and docs/order-workflow.md.
            recordRequiresReview(paymentId, ReconciliationReason.AUTHORIZED_WITHOUT_RESERVED_STOCK);
            return;
        }

        List<InventoryReservation> reservations = reservationsForOrderInDeterministicOrder(orderId);
        for (InventoryReservation reservation : reservations) {
            reservationService.confirm(reservation.getId());
        }

        order.setStatus(OrderStatus.CONFIRMED);
        orderRepository.save(order);
        outboxEventWriter.write(AggregateType.ORDER, orderId, OutboxEventType.ORDER_CONFIRMED,
            new OrderConfirmedPayload(orderId, OrderStatus.CONFIRMED.name(), order.getTotalAmount()));
    }

    /**
     * PENDING -&gt; CANCELLED, and every reservation for the order compensated: ACTIVE -&gt;
     * RELEASED (restoring its quantity to {@code inventory.available_quantity} exactly once,
     * reusing {@link ReservationService#release}'s own proven idempotency), RELEASED already
     * compensated (no-op), EXPIRED already compensated by independent expiration (skipped
     * entirely — {@code release} would reject that transition, and no restoration is needed since
     * expiration already restored it). A CONFIRMED reservation is a conflict: this order's stock
     * was already permanently confirmed, so a FAILED payment arriving for it is contradictory.
     */
    @Transactional
    public void handlePaymentFailed(UUID eventId, UUID paymentId, UUID orderId) {
        int inserted = claimReceipt(eventId, OutboxEventType.PAYMENT_FAILED, paymentId, orderId);
        if (inserted == 0) {
            return;
        }

        Payment payment = requirePayment(paymentId, orderId);
        if (payment.getStatus() != PaymentStatus.FAILED) {
            throw workflowConflict("payment_state_mismatch", "Payment " + paymentId
                + " is not FAILED in PostgreSQL; refusing to trust the Kafka event payload alone");
        }

        Order order = requireOrderLocked(orderId);
        switch (order.getStatus()) {
            case CANCELLED -> {
                return; // idempotent no-op: this outcome was already applied by an earlier event
            }
            case CONFIRMED -> throw workflowConflict("conflicting_order_transition",
                "Order " + orderId + " is already CONFIRMED; refusing to apply FAILED");
            case PENDING -> {
                // proceed below
            }
        }

        List<InventoryReservation> reservations = reservationsForOrderInDeterministicOrder(orderId);
        for (InventoryReservation reservation : reservations) {
            if (reservation.getStatus() == ReservationStatus.EXPIRED) {
                continue; // already compensated by independent expiration; nothing left to do
            }
            reservationService.release(reservation.getId());
        }

        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);
        outboxEventWriter.write(AggregateType.ORDER, orderId, OutboxEventType.ORDER_CANCELLED,
            new OrderCancelledPayload(orderId, OrderStatus.CANCELLED.name()));
    }

    /**
     * @return the affected-row count from the underlying INSERT: 1 for a genuinely new event ID
     *     (first processing attempt), 0 for a duplicate delivery of an event ID already recorded.
     *     A duplicate whose recorded identity (event type / payment / order) does not match this
     *     delivery's is rejected outright — reused event IDs must never silently mean a different
     *     logical event.
     */
    private int claimReceipt(UUID eventId, OutboxEventType eventType, UUID paymentId, UUID orderId) {
        int inserted = receiptRepository.tryInsert(eventId, eventType.name(), paymentId, orderId, Instant.now());
        if (inserted == 0) {
            OrderWorkflowEventReceipt existing = receiptRepository.findById(eventId)
                .orElseThrow(() -> new IllegalStateException(
                    "receipt insert reported a duplicate but no row exists for event " + eventId));
            if (!existing.getEventType().equals(eventType.name()) || !existing.getPaymentId().equals(paymentId)
                || !existing.getOrderId().equals(orderId)) {
                throw workflowConflict("event_identity_conflict", "Event " + eventId
                    + " was already recorded with a different identity (existing: type=" + existing.getEventType()
                    + " payment=" + existing.getPaymentId() + " order=" + existing.getOrderId() + "; incoming: type="
                    + eventType + " payment=" + paymentId + " order=" + orderId + ")");
            }
        }
        return inserted;
    }

    private Payment requirePayment(UUID paymentId, UUID orderId) {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> workflowConflict("unknown_payment",
                "Workflow event referenced unknown payment " + paymentId));
        if (!payment.getOrderId().equals(orderId)) {
            throw workflowConflict("payment_order_mismatch", "Payment " + paymentId + " belongs to order "
                + payment.getOrderId() + ", not " + orderId + " as the workflow event claimed");
        }
        return payment;
    }

    private Order requireOrderLocked(UUID orderId) {
        return orderRepository.findByIdForUpdate(orderId)
            .orElseThrow(() -> workflowConflict("unknown_order", "Workflow event referenced unknown order " + orderId));
    }

    /**
     * Durable evidence of a known, permanent business conflict this workflow deliberately refuses
     * to repair automatically — the same {@code payment_reconciliation_cases} table
     * {@code PaymentReconciliationApplier} writes to, so a case created here and one created by an
     * explicit reconciliation attempt are the same durable record, not two competing ones.
     * {@code last_provider_status} is left {@code null}: this conflict was detected from already-
     * committed local state (payment AUTHORIZED, reservation lost), not from a fresh provider
     * query.
     */
    private void recordRequiresReview(UUID paymentId, ReconciliationReason reason) {
        reconciliationCaseRepository.upsert(UUID.randomUUID(), paymentId, ReconciliationStatus.REQUIRES_REVIEW.name(),
            null, reason.name(), Instant.now(), Instant.now());
    }

    private List<InventoryReservation> reservationsForOrderInDeterministicOrder(UUID orderId) {
        return reservationService.getReservationsForOrder(orderId).stream()
            .sorted(Comparator.comparing(InventoryReservation::getSku).thenComparing(InventoryReservation::getId))
            .toList();
    }

    private static BusinessRuleViolation workflowConflict(String code, String message) {
        return new BusinessRuleViolation(HttpStatus.CONFLICT, code, message);
    }
}

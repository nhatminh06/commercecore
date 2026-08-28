package com.commercecore.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercecore.AbstractIntegrationTest;
import com.commercecore.cart.CartService;
import com.commercecore.catalog.ProductService;
import com.commercecore.inventory.InventoryService;
import com.commercecore.outbox.OutboxEvent;
import com.commercecore.outbox.OutboxEventRepository;
import com.commercecore.outbox.OutboxEventType;
import com.commercecore.payment.FakePaymentProvider;
import com.commercecore.payment.Payment;
import com.commercecore.payment.PaymentReconciliationCase;
import com.commercecore.payment.PaymentReconciliationCaseRepository;
import com.commercecore.payment.PaymentService;
import com.commercecore.payment.ReconciliationReason;
import com.commercecore.payment.ReconciliationStatus;
import com.commercecore.reservation.InventoryReservation;
import com.commercecore.reservation.ReservationService;
import com.commercecore.reservation.ReservationStatus;
import com.commercecore.shared.BusinessRuleViolation;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Direct, Kafka-free tests of {@link OrderPaymentWorkflowService} — the transactional business
 * logic itself, exercised through real PostgreSQL (checkout, reservation, payment, outbox all
 * real) but with the workflow method called directly rather than via Kafka delivery. Physical
 * duplicate-Kafka-delivery and consumer-group scenarios live in
 * {@code OrderPaymentWorkflowKafkaTest} instead; what matters here is the underlying transaction
 * semantics, which do not require a broker to prove.
 */
class OrderPaymentWorkflowServiceTest extends AbstractIntegrationTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private CartService cartService;

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private FakePaymentProvider fakePaymentProvider;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OrderWorkflowEventReceiptRepository receiptRepository;

    @Autowired
    private OrderPaymentWorkflowService workflowService;

    @Autowired
    private PaymentReconciliationCaseRepository reconciliationCaseRepository;

    private String newProductWithStock(int stock) {
        String sku = "SKU-" + UUID.randomUUID();
        productService.createProduct(sku, "Widget", new BigDecimal("10.00"), stock);
        return sku;
    }

    private UUID checkout(String sku, int quantity) {
        UUID cartId = cartService.createCart().getId();
        cartService.setItemQuantity(cartId, sku, quantity);
        return checkoutService.checkoutIdempotently(cartId, "key-" + UUID.randomUUID()).order().getId();
    }

    private Payment authorize(UUID orderId) {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.SUCCESS);
        return paymentService.initiatePayment(orderId);
    }

    private Payment decline(UUID orderId) {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.DECLINED);
        return paymentService.initiatePayment(orderId);
    }

    private List<OutboxEvent> orderEventsOf(UUID orderId, OutboxEventType type) {
        return outboxEventRepository.findAll().stream()
            .filter(e -> e.getEventType() == type && e.getAggregateId().equals(orderId))
            .toList();
    }

    // --- Basic AUTHORIZED / FAILED (tests 29-30) -------------------------------------------

    @Test
    void authorizedConfirmsOrderAndReservationsWithoutChangingInventory() {
        String sku = newProductWithStock(5);
        UUID orderId = checkout(sku, 2);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(3);

        Payment payment = authorize(orderId);
        workflowService.handlePaymentAuthorized(UUID.randomUUID(), payment.getId(), orderId);

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        List<InventoryReservation> reservations = reservationService.getReservationsForOrder(orderId);
        assertThat(reservations).allMatch(r -> r.getStatus() == ReservationStatus.CONFIRMED);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(3);
        assertThat(orderEventsOf(orderId, OutboxEventType.ORDER_CONFIRMED)).hasSize(1);
    }

    @Test
    void failedCancelsOrderAndRestoresInventory() {
        String sku = newProductWithStock(5);
        UUID orderId = checkout(sku, 2);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(3);

        Payment payment = decline(orderId);
        workflowService.handlePaymentFailed(UUID.randomUUID(), payment.getId(), orderId);

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        List<InventoryReservation> reservations = reservationService.getReservationsForOrder(orderId);
        assertThat(reservations).allMatch(r -> r.getStatus() == ReservationStatus.RELEASED);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(5);
        assertThat(orderEventsOf(orderId, OutboxEventType.ORDER_CANCELLED)).hasSize(1);
    }

    // --- Multi-SKU (tests 31-32) ------------------------------------------------------------

    @Test
    void multiSkuAuthorizedConfirmsEveryReservationWithoutRestoringStock() {
        String skuA = newProductWithStock(5);
        String skuB = newProductWithStock(10);
        UUID cartId = cartService.createCart().getId();
        cartService.setItemQuantity(cartId, skuA, 2);
        cartService.setItemQuantity(cartId, skuB, 3);
        UUID orderId = checkoutService.checkoutIdempotently(cartId, "key-" + UUID.randomUUID()).order().getId();
        assertThat(inventoryService.getBySku(skuA).getAvailableQuantity()).isEqualTo(3);
        assertThat(inventoryService.getBySku(skuB).getAvailableQuantity()).isEqualTo(7);

        Payment payment = authorize(orderId);
        workflowService.handlePaymentAuthorized(UUID.randomUUID(), payment.getId(), orderId);

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(reservationService.getReservationsForOrder(orderId))
            .allMatch(r -> r.getStatus() == ReservationStatus.CONFIRMED);
        assertThat(inventoryService.getBySku(skuA).getAvailableQuantity()).isEqualTo(3);
        assertThat(inventoryService.getBySku(skuB).getAvailableQuantity()).isEqualTo(7);
    }

    @Test
    void multiSkuFailedRestoresAllStock() {
        String skuA = newProductWithStock(5);
        String skuB = newProductWithStock(10);
        UUID cartId = cartService.createCart().getId();
        cartService.setItemQuantity(cartId, skuA, 2);
        cartService.setItemQuantity(cartId, skuB, 3);
        UUID orderId = checkoutService.checkoutIdempotently(cartId, "key-" + UUID.randomUUID()).order().getId();

        Payment payment = decline(orderId);
        workflowService.handlePaymentFailed(UUID.randomUUID(), payment.getId(), orderId);

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(reservationService.getReservationsForOrder(orderId))
            .allMatch(r -> r.getStatus() == ReservationStatus.RELEASED);
        assertThat(inventoryService.getBySku(skuA).getAvailableQuantity()).isEqualTo(5);
        assertThat(inventoryService.getBySku(skuB).getAvailableQuantity()).isEqualTo(10);
    }

    // --- Same event ID redelivered directly (sanity companion to the mandatory Kafka test) --

    @Test
    void sameEventIdCalledRepeatedlyConfirmsOrderOnlyOnce() {
        String sku = newProductWithStock(5);
        UUID orderId = checkout(sku, 2);
        Payment payment = authorize(orderId);
        UUID eventId = UUID.randomUUID();

        for (int i = 0; i < 20; i++) {
            workflowService.handlePaymentAuthorized(eventId, payment.getId(), orderId);
        }

        assertThat(receiptRepository.findAll().stream().filter(r -> r.getEventId().equals(eventId)).count())
            .isEqualTo(1);
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(orderEventsOf(orderId, OutboxEventType.ORDER_CONFIRMED)).hasSize(1);
    }

    @Test
    void sameEventIdCalledRepeatedlyRestoresInventoryOnlyOnce() {
        String sku = newProductWithStock(5);
        UUID orderId = checkout(sku, 2);
        Payment payment = decline(orderId);
        UUID eventId = UUID.randomUUID();

        for (int i = 0; i < 20; i++) {
            workflowService.handlePaymentFailed(eventId, payment.getId(), orderId);
        }

        assertThat(receiptRepository.findAll().stream().filter(r -> r.getEventId().equals(eventId)).count())
            .isEqualTo(1);
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(5);
        assertThat(orderEventsOf(orderId, OutboxEventType.ORDER_CANCELLED)).hasSize(1);
    }

    // --- Different event IDs, same outcome (tests 35-36) -------------------------------------

    @Test
    void differentEventIdsSameAuthorizedOutcomeConfirmOnceButRecordTwoReceipts() {
        String sku = newProductWithStock(5);
        UUID orderId = checkout(sku, 2);
        Payment payment = authorize(orderId);
        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();

        workflowService.handlePaymentAuthorized(e1, payment.getId(), orderId);
        workflowService.handlePaymentAuthorized(e2, payment.getId(), orderId);

        assertThat(receiptRepository.findById(e1)).isPresent();
        assertThat(receiptRepository.findById(e2)).isPresent();
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(reservationService.getReservationsForOrder(orderId))
            .allMatch(r -> r.getStatus() == ReservationStatus.CONFIRMED);
        assertThat(orderEventsOf(orderId, OutboxEventType.ORDER_CONFIRMED)).hasSize(1);
    }

    @Test
    void differentEventIdsSameFailedOutcomeCancelOnceButRestoreInventoryOnce() {
        String sku = newProductWithStock(5);
        UUID orderId = checkout(sku, 2);
        Payment payment = decline(orderId);
        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();

        workflowService.handlePaymentFailed(e1, payment.getId(), orderId);
        workflowService.handlePaymentFailed(e2, payment.getId(), orderId);

        assertThat(receiptRepository.findById(e1)).isPresent();
        assertThat(receiptRepository.findById(e2)).isPresent();
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(5);
        assertThat(orderEventsOf(orderId, OutboxEventType.ORDER_CANCELLED)).hasSize(1);
    }

    // --- Concurrent distinct events, same order (test 38) ------------------------------------

    @Test
    void concurrentDistinctAuthorizedEventsForSameOrderSerializeToOneTransition() throws Exception {
        String sku = newProductWithStock(5);
        UUID orderId = checkout(sku, 2);
        Payment payment = authorize(orderId);
        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch done = new CountDownLatch(2);
        try {
            executor.submit(() -> {
                try {
                    barrier.await();
                    workflowService.handlePaymentAuthorized(e1, payment.getId(), orderId);
                } catch (Exception ignored) {
                    // acceptable if the underlying transaction retries are exhausted; assertions below decide
                } finally {
                    done.countDown();
                }
            });
            executor.submit(() -> {
                try {
                    barrier.await();
                    workflowService.handlePaymentAuthorized(e2, payment.getId(), orderId);
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdown();
        }

        assertThat(receiptRepository.findById(e1)).isPresent();
        assertThat(receiptRepository.findById(e2)).isPresent();
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(orderEventsOf(orderId, OutboxEventType.ORDER_CONFIRMED)).hasSize(1);
    }

    // --- Conflicting terminal transitions (test 39) -------------------------------------------

    @Test
    void conflictingFailedEventAfterConfirmedOrderIsRejected() {
        String sku = newProductWithStock(5);
        UUID orderId = checkout(sku, 2);
        Payment payment = authorize(orderId);
        workflowService.handlePaymentAuthorized(UUID.randomUUID(), payment.getId(), orderId);
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);

        // Simulate a corrupt/contradictory FAILED event for the same, already-AUTHORIZED payment
        // by calling handlePaymentFailed directly against the real (AUTHORIZED) payment truth —
        // the payment-state check rejects it before the order-conflict check is even reached,
        // which is itself part of what this test proves: nothing downstream ever runs.
        UUID conflictingEventId = UUID.randomUUID();
        assertThatThrownBy(
            () -> workflowService.handlePaymentFailed(conflictingEventId, payment.getId(), orderId))
            .isInstanceOf(BusinessRuleViolation.class);

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(reservationService.getReservationsForOrder(orderId))
            .allMatch(r -> r.getStatus() == ReservationStatus.CONFIRMED);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(3);
        assertThat(orderEventsOf(orderId, OutboxEventType.ORDER_CANCELLED)).isEmpty();
        assertThat(receiptRepository.findById(conflictingEventId)).isEmpty();
    }

    // --- Payment state mismatch (test 40) -------------------------------------------------

    @Test
    void authorizedEventRejectedWhenPaymentIsActuallyFailedInPostgres() {
        String sku = newProductWithStock(5);
        UUID orderId = checkout(sku, 2);
        Payment payment = decline(orderId);
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> workflowService.handlePaymentAuthorized(eventId, payment.getId(), orderId))
            .isInstanceOf(BusinessRuleViolation.class);

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(reservationService.getReservationsForOrder(orderId))
            .allMatch(r -> r.getStatus() == ReservationStatus.ACTIVE);
        assertThat(orderEventsOf(orderId, OutboxEventType.ORDER_CONFIRMED)).isEmpty();
        assertThat(receiptRepository.findById(eventId)).isEmpty();
    }

    @Test
    void failedEventRejectedWhenPaymentIsActuallyAuthorizedInPostgres() {
        String sku = newProductWithStock(5);
        UUID orderId = checkout(sku, 2);
        Payment payment = authorize(orderId);
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> workflowService.handlePaymentFailed(eventId, payment.getId(), orderId))
            .isInstanceOf(BusinessRuleViolation.class);

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(reservationService.getReservationsForOrder(orderId))
            .allMatch(r -> r.getStatus() == ReservationStatus.ACTIVE);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(3);
        assertThat(orderEventsOf(orderId, OutboxEventType.ORDER_CANCELLED)).isEmpty();
        assertThat(receiptRepository.findById(eventId)).isEmpty();
    }

    // --- Expired reservation edge cases (tests 41-42) -----------------------------------------

    @Test
    void authorizedAfterExpiredReservationRecordsRequiresReviewInsteadOfConfirming() {
        String sku = newProductWithStock(5);
        UUID orderId = checkout(sku, 2);
        InventoryReservation reservation = reservationService.getReservationsForOrder(orderId).get(0);
        reservationService.expireDueReservations(reservation.getExpiresAt());
        assertThat(reservationService.getReservation(reservation.getId()).getStatus())
            .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(5);

        Payment payment = authorize(orderId);
        UUID eventId = UUID.randomUUID();

        // Milestone 11: this known-permanent conflict no longer throws/rolls back forever — it
        // commits durable REQUIRES_REVIEW evidence and this event's receipt, then returns
        // normally (so Kafka acknowledges instead of redelivering a poison event indefinitely).
        workflowService.handlePaymentAuthorized(eventId, payment.getId(), orderId);

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(reservationService.getReservation(reservation.getId()).getStatus())
            .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(5);
        assertThat(orderEventsOf(orderId, OutboxEventType.ORDER_CONFIRMED)).isEmpty();
        assertThat(receiptRepository.findById(eventId)).isPresent();

        PaymentReconciliationCase reconciliationCase =
            reconciliationCaseRepository.findByPaymentId(payment.getId()).orElseThrow();
        assertThat(reconciliationCase.getStatus()).isEqualTo(ReconciliationStatus.REQUIRES_REVIEW);
        assertThat(reconciliationCase.getReason()).isEqualTo(ReconciliationReason.AUTHORIZED_WITHOUT_RESERVED_STOCK);
    }

    @Test
    void failedAfterExpiredReservationCancelsOrderWithoutDoubleRestoringInventory() {
        String sku = newProductWithStock(5);
        UUID orderId = checkout(sku, 2);
        InventoryReservation reservation = reservationService.getReservationsForOrder(orderId).get(0);
        reservationService.expireDueReservations(reservation.getExpiresAt());
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(5);

        Payment payment = decline(orderId);
        workflowService.handlePaymentFailed(UUID.randomUUID(), payment.getId(), orderId);

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(reservationService.getReservation(reservation.getId()).getStatus())
            .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(5);
        assertThat(orderEventsOf(orderId, OutboxEventType.ORDER_CANCELLED)).hasSize(1);
    }

    // --- Event-identity conflict: same eventId reused for different content (section 16) -----

    @Test
    void sameEventIdReusedForDifferentPaymentIsRejectedAsAConflict() {
        String skuA = newProductWithStock(5);
        String skuB = newProductWithStock(5);
        UUID orderA = checkout(skuA, 1);
        UUID orderB = checkout(skuB, 1);
        Payment paymentA = authorize(orderA);
        Payment paymentB = authorize(orderB);
        UUID eventId = UUID.randomUUID();

        workflowService.handlePaymentAuthorized(eventId, paymentA.getId(), orderA);

        assertThatThrownBy(() -> workflowService.handlePaymentAuthorized(eventId, paymentB.getId(), orderB))
            .isInstanceOf(BusinessRuleViolation.class);

        // The original receipt/effect for order A is untouched; order B was never touched at all
        // — this is also the receipt+side-effect atomicity proof (test 43): the conflicting call
        // rolled back completely, leaving no partial state anywhere.
        assertThat(orderRepository.findById(orderA).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(orderRepository.findById(orderB).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(reservationService.getReservationsForOrder(orderB))
            .allMatch(r -> r.getStatus() == ReservationStatus.ACTIVE);
        assertThat(orderEventsOf(orderB, OutboxEventType.ORDER_CONFIRMED)).isEmpty();
    }
}

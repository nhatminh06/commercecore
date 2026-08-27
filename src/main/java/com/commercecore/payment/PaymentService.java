package com.commercecore.payment;

import com.commercecore.checkout.Order;
import com.commercecore.checkout.OrderRepository;
import com.commercecore.shared.BusinessRuleViolation;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Deliberately <strong>not</strong> {@code @Transactional}. Payment initiation has three phases
 * that must not share one database transaction:
 *
 * <ol>
 *   <li><strong>Phase A</strong> — {@link PaymentRepository#tryCreatePayment} persists intent
 *       (a PENDING payment row) and commits, in its own short transaction.
 *   <li><strong>Phase B</strong> — {@link PaymentProvider#authorize} is called with no database
 *       transaction open at all.
 *   <li><strong>Phase C</strong> — persists the outcome in another short transaction: on
 *       success/decline, {@link PaymentTransitionService#resolveToAuthorized} or
 *       {@link PaymentTransitionService#resolveToFailed} (which also atomically writes the
 *       matching outbox event — see {@code docs/outbox.md}); on timeout,
 *       {@link PaymentRepository#markUnknown} (no outbox event: CommerceCore doesn't yet know
 *       whether authorization actually happened).
 * </ol>
 *
 * <p>If Phase A and Phase C were the same transaction as Phase B, a provider timeout — an
 * exception — would roll back the whole transaction, including the PENDING row Phase A wrote.
 * That would make a persisted UNKNOWN outcome impossible: there would be nothing left to mark
 * UNKNOWN. Splitting the phases is what lets "the provider call failed to complete" become
 * observable database state instead of vanishing along with the failed call. See
 * {@code docs/payments.md}.
 *
 * <p>The other side of that split: PostgreSQL cannot roll back a call that already reached the
 * provider. Once Phase B has been invoked, whatever happened on the provider's side already
 * happened — there is no ROLLBACK for it. That's the entire reason UNKNOWN exists.
 */
@Service
public class PaymentService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentProvider paymentProvider;
    private final PaymentTransitionService paymentTransitionService;

    public PaymentService(OrderRepository orderRepository, PaymentRepository paymentRepository,
        PaymentProvider paymentProvider, PaymentTransitionService paymentTransitionService) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.paymentProvider = paymentProvider;
        this.paymentTransitionService = paymentTransitionService;
    }

    /**
     * Ensures exactly one payment row exists for the order, and that at most one caller ever
     * calls the provider for it. Ownership of "the one caller who calls the provider" is decided
     * entirely by {@link PaymentRepository#tryCreatePayment}'s affected-row count: whoever's
     * INSERT actually lands is the owner and proceeds through Phase B/C below; every other
     * caller — whether the payment was already resolved from an earlier call, or another
     * concurrent caller is mid-flight right now — reads back the current row and returns,
     * without ever invoking {@link #paymentProvider}. This is what makes repeated initiation
     * after AUTHORIZED, FAILED, or UNKNOWN alike a safe no-op: none of them re-enter Phase B,
     * for the same underlying reason.
     */
    public Payment initiatePayment(UUID orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new BusinessRuleViolation(HttpStatus.NOT_FOUND, "order_not_found",
                "Unknown order: " + orderId));

        UUID candidatePaymentId = UUID.randomUUID();
        Instant now = Instant.now();
        int created = paymentRepository.tryCreatePayment(candidatePaymentId, orderId, order.getTotalAmount(), now);

        Payment payment = paymentRepository.findByOrderId(orderId)
            .orElseThrow(() -> new IllegalStateException("payment must exist immediately after tryCreatePayment"));

        if (created == 0) {
            return payment;
        }

        try {
            AuthorizationResult result = paymentProvider.authorize(payment.getId(), payment.getAmount());
            switch (result) {
                case AuthorizationResult.Authorized authorized -> paymentTransitionService.resolveToAuthorized(
                    payment.getId(), orderId, payment.getAmount(), authorized.providerReference());
                case AuthorizationResult.Declined declined ->
                    paymentTransitionService.resolveToFailed(payment.getId(), orderId, payment.getAmount());
            }
        } catch (PaymentProviderTimeoutException e) {
            paymentRepository.markUnknown(payment.getId(), Instant.now());
        }

        return paymentRepository.findByOrderId(orderId)
            .orElseThrow(() -> new IllegalStateException("payment must still exist after outcome persisted"));
    }

    public Payment getPaymentByOrder(UUID orderId) {
        return paymentRepository.findByOrderId(orderId)
            .orElseThrow(() -> new BusinessRuleViolation(HttpStatus.NOT_FOUND, "payment_not_found",
                "No payment for order: " + orderId));
    }
}

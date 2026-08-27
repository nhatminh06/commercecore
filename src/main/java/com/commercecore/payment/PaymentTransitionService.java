package com.commercecore.payment;

import com.commercecore.outbox.AggregateType;
import com.commercecore.outbox.OutboxEventType;
import com.commercecore.outbox.OutboxEventWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single place a payment actually transitions to AUTHORIZED or FAILED, shared by both paths
 * that can cause it: {@link PaymentService#initiatePayment}'s synchronous provider call, and
 * {@link PaymentWebhookService}'s asynchronous webhook evidence. Sharing this one component is
 * what makes it structurally impossible for "state transition" and "outbox event" to diverge
 * between the two call sites — there's only one place either happens.
 *
 * <p>Each method here is atomic with its own outbox insert: the conditional UPDATE only emits an
 * event when it actually changed a row (an idempotent no-op — the payment was already in that
 * state — never emits a duplicate event). Being a different Spring bean from both callers means
 * this works correctly either way: called from {@code PaymentService.initiatePayment} (not
 * transactional), this opens its own short transaction; called from
 * {@code PaymentWebhookService.processWebhook} (already transactional), it simply joins that
 * transaction — same code, correct either way, via ordinary {@code REQUIRED} propagation.
 */
@Service
public class PaymentTransitionService {

    private final PaymentRepository paymentRepository;
    private final OutboxEventWriter outboxEventWriter;

    public PaymentTransitionService(PaymentRepository paymentRepository, OutboxEventWriter outboxEventWriter) {
        this.paymentRepository = paymentRepository;
        this.outboxEventWriter = outboxEventWriter;
    }

    /**
     * @return {@code true} if this call actually transitioned the payment (PENDING/UNKNOWN ->
     *     AUTHORIZED); {@code false} if the payment was already AUTHORIZED (an idempotent no-op,
     *     already validated by the caller — no outbox event either way for a no-op).
     */
    @Transactional
    public boolean resolveToAuthorized(UUID paymentId, UUID orderId, BigDecimal amount, String providerReference) {
        int updated = paymentRepository.resolveToAuthorized(paymentId, providerReference, Instant.now());
        if (updated == 1) {
            outboxEventWriter.write(AggregateType.PAYMENT, paymentId, OutboxEventType.PAYMENT_AUTHORIZED,
                new PaymentAuthorizedPayload(paymentId, orderId, amount, providerReference));
        }
        return updated == 1;
    }

    /**
     * @return {@code true} if this call actually transitioned the payment (PENDING/UNKNOWN ->
     *     FAILED); {@code false} for an idempotent no-op.
     */
    @Transactional
    public boolean resolveToFailed(UUID paymentId, UUID orderId, BigDecimal amount) {
        int updated = paymentRepository.resolveToFailed(paymentId, Instant.now());
        if (updated == 1) {
            outboxEventWriter.write(AggregateType.PAYMENT, paymentId, OutboxEventType.PAYMENT_FAILED,
                new PaymentFailedPayload(paymentId, orderId, amount));
        }
        return updated == 1;
    }
}

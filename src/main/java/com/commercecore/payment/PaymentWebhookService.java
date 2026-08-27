package com.commercecore.payment;

import com.commercecore.shared.BusinessRuleViolation;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provider evidence arriving asynchronously — never a call back to
 * {@link PaymentProvider#authorize}. Unlike payment initiation, this makes no external call at
 * all, so (unlike {@link PaymentService}) this class is safely one {@code @Transactional}
 * method: event receipt, payment transition, and (via {@link PaymentTransitionService}, which
 * joins this already-active transaction) the corresponding outbox event all commit together or
 * roll back together, by ordinary transaction semantics — no phase-splitting hazard to reason
 * about here.
 */
@Service
public class PaymentWebhookService {

    private static final int MAX_EVENT_ID_LENGTH = 255;

    private final PaymentRepository paymentRepository;
    private final PaymentWebhookEventRepository webhookEventRepository;
    private final PaymentTransitionService paymentTransitionService;

    public PaymentWebhookService(PaymentRepository paymentRepository,
        PaymentWebhookEventRepository webhookEventRepository, PaymentTransitionService paymentTransitionService) {
        this.paymentRepository = paymentRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.paymentTransitionService = paymentTransitionService;
    }

    /**
     * Claim the event ({@code INSERT ... ON CONFLICT DO NOTHING}), then:
     *
     * <ul>
     *   <li><strong>Claimed (1 row):</strong> this delivery is the first to see this event ID.
     *       Lock the payment row ({@link PaymentRepository#findByIdForUpdate}) and apply
     *       {@link #applyTransition}. If the transition is illegal (a terminal-state conflict or
     *       a provider-reference mismatch), it throws — rolling back the whole transaction,
     *       <em>including the event receipt just inserted</em>. That is deliberate: a "processed
     *       event" should always mean "was accepted and applied," never "was received but
     *       rejected." A retried delivery of a genuinely conflicting event will therefore hit the
     *       same conflict again, every time, rather than being silently swallowed after the first
     *       attempt.
     *   <li><strong>Not claimed (0 rows):</strong> a receipt for this event ID already exists.
     *       Compare the incoming payload against the stored one. Identical → this is a safe
     *       duplicate delivery; return the current payment state without touching it again.
     *       Different → the same event ID is being reused for a different logical event, which
     *       is a protocol violation, not a retry — rejected outright.
     * </ul>
     */
    @Transactional
    public Payment processWebhook(String eventId, String providerRequestId, String rawType, String providerReference) {
        validateEventId(eventId);
        UUID paymentId = parsePaymentId(providerRequestId);
        WebhookEventType type = parseEventType(rawType);
        if (type == WebhookEventType.PAYMENT_AUTHORIZED && (providerReference == null || providerReference.isBlank())) {
            throw new BusinessRuleViolation(HttpStatus.BAD_REQUEST, "invalid_webhook_event",
                "providerReference is required for PAYMENT_AUTHORIZED events");
        }
        // Checked explicitly, before attempting the event INSERT: payment_id is a foreign key on
        // payment_webhook_events, so an unknown payment would otherwise surface as a generic
        // constraint-violation error rather than a clean payment_not_found.
        if (!paymentRepository.existsById(paymentId)) {
            throw new BusinessRuleViolation(HttpStatus.NOT_FOUND, "payment_not_found", "Unknown payment: " + paymentId);
        }

        Instant now = Instant.now();
        int claimed = webhookEventRepository.tryClaimEvent(eventId, paymentId, type.name(), providerReference, now);

        if (claimed == 0) {
            return handleDuplicateDelivery(eventId, paymentId, type, providerReference);
        }

        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow(() -> new BusinessRuleViolation(HttpStatus.NOT_FOUND, "payment_not_found",
                "Unknown payment: " + paymentId));

        applyTransition(payment, type, providerReference);

        return paymentRepository.findById(paymentId)
            .orElseThrow(() -> new IllegalStateException("payment must still exist after transition"));
    }

    private Payment handleDuplicateDelivery(String eventId, UUID paymentId, WebhookEventType type,
        String providerReference) {
        PaymentWebhookEvent existing = webhookEventRepository.findById(eventId)
            .orElseThrow(() -> new IllegalStateException("event must exist immediately after a failed claim"));

        boolean samePayload = existing.getPaymentId().equals(paymentId)
            && existing.getEventType() == type
            && Objects.equals(existing.getProviderReference(), providerReference);
        if (!samePayload) {
            throw new BusinessRuleViolation(HttpStatus.CONFLICT, "provider_event_id_reused",
                "Provider event " + eventId + " was already processed with different details");
        }

        return paymentRepository.findById(paymentId)
            .orElseThrow(() -> new BusinessRuleViolation(HttpStatus.NOT_FOUND, "payment_not_found",
                "Unknown payment: " + paymentId));
    }

    /**
     * Runs under the row lock acquired by the caller. PENDING and UNKNOWN are treated alike —
     * both are "not yet resolved" from the webhook's point of view, and either legitimately
     * becomes AUTHORIZED or FAILED on authoritative provider evidence. AUTHORIZED and FAILED are
     * terminal: a same-outcome repeat (same provider reference, for AUTHORIZED) is a safe no-op;
     * anything else is an explicit conflict, never a silent overwrite.
     */
    private void applyTransition(Payment payment, WebhookEventType type, String providerReference) {
        switch (payment.getStatus()) {
            case PENDING, UNKNOWN -> {
                if (type == WebhookEventType.PAYMENT_AUTHORIZED) {
                    paymentTransitionService.resolveToAuthorized(payment.getId(), payment.getOrderId(),
                        payment.getAmount(), providerReference);
                } else {
                    paymentTransitionService.resolveToFailed(payment.getId(), payment.getOrderId(), payment.getAmount());
                }
            }
            case AUTHORIZED -> {
                if (type == WebhookEventType.PAYMENT_DECLINED) {
                    throw new BusinessRuleViolation(HttpStatus.CONFLICT, "conflicting_payment_event",
                        "Payment " + payment.getId() + " is already AUTHORIZED; cannot apply a DECLINED event");
                }
                if (!Objects.equals(payment.getProviderReference(), providerReference)) {
                    throw new BusinessRuleViolation(HttpStatus.CONFLICT, "conflicting_payment_event",
                        "Payment " + payment.getId() + " is already AUTHORIZED with a different provider reference");
                }
                // Same outcome, same reference: idempotent no-op.
            }
            case FAILED -> {
                if (type == WebhookEventType.PAYMENT_AUTHORIZED) {
                    throw new BusinessRuleViolation(HttpStatus.CONFLICT, "conflicting_payment_event",
                        "Payment " + payment.getId() + " is already FAILED; cannot apply an AUTHORIZED event");
                }
                // Same outcome: idempotent no-op.
            }
        }
    }

    private void validateEventId(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            throw new BusinessRuleViolation(HttpStatus.BAD_REQUEST, "invalid_webhook_event", "eventId is required");
        }
        if (eventId.length() > MAX_EVENT_ID_LENGTH) {
            throw new BusinessRuleViolation(HttpStatus.BAD_REQUEST, "invalid_webhook_event",
                "eventId must be at most " + MAX_EVENT_ID_LENGTH + " characters");
        }
    }

    private UUID parsePaymentId(String providerRequestId) {
        if (providerRequestId == null || providerRequestId.isBlank()) {
            throw new BusinessRuleViolation(HttpStatus.BAD_REQUEST, "invalid_webhook_event",
                "providerRequestId is required");
        }
        try {
            return UUID.fromString(providerRequestId);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleViolation(HttpStatus.BAD_REQUEST, "invalid_webhook_event",
                "providerRequestId is not a valid identifier");
        }
    }

    private WebhookEventType parseEventType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            throw new BusinessRuleViolation(HttpStatus.BAD_REQUEST, "invalid_webhook_event", "type is required");
        }
        try {
            return WebhookEventType.valueOf(rawType);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleViolation(HttpStatus.BAD_REQUEST, "invalid_webhook_event",
                "Unknown event type: " + rawType);
        }
    }
}

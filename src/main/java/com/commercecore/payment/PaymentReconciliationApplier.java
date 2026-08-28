package com.commercecore.payment;

import com.commercecore.reservation.ReservationService;
import com.commercecore.shared.BusinessRuleViolation;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The atomic "apply one already-obtained provider lookup result" phase of reconciliation — a
 * separate Spring bean from {@link PaymentReconciliationService} for the same reason
 * {@code PaymentTransitionService} is separate from {@code PaymentService}: the provider call
 * must happen with no database transaction open, so the phase that opens one has to be a distinct
 * cross-bean call, not a same-bean method Spring's transactional proxy would silently skip.
 *
 * <p>Locks the payment ({@code findByIdForUpdate}), decides based on the payment's <em>current</em>
 * (re-read) status — never the caller's earlier snapshot, since a webhook or a concurrent
 * reconciliation attempt may have changed it while the provider was being queried — and applies at
 * most one of: a real payment transition (via {@link PaymentTransitionService}, which also writes
 * the accompanying outbox event), a reconciliation-case upsert, or both, all in one transaction.
 */
@Service
public class PaymentReconciliationApplier {

    private final PaymentRepository paymentRepository;
    private final PaymentTransitionService paymentTransitionService;
    private final PaymentReconciliationCaseRepository caseRepository;
    private final ReservationService reservationService;

    public PaymentReconciliationApplier(PaymentRepository paymentRepository,
        PaymentTransitionService paymentTransitionService, PaymentReconciliationCaseRepository caseRepository,
        ReservationService reservationService) {
        this.paymentRepository = paymentRepository;
        this.paymentTransitionService = paymentTransitionService;
        this.caseRepository = caseRepository;
        this.reservationService = reservationService;
    }

    @Transactional
    public Optional<PaymentReconciliationCase> apply(UUID paymentId, ProviderLookupResult lookup) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow(() -> new BusinessRuleViolation(HttpStatus.NOT_FOUND, "payment_not_found",
                "Unknown payment: " + paymentId));
        Instant now = Instant.now();

        return switch (lookup.status()) {
            case NOT_FOUND -> handleNotFound(payment, now);
            case AUTHORIZED -> Optional.of(handleProviderAuthorized(payment, lookup, now));
            case DECLINED -> Optional.of(handleProviderDeclined(payment, now));
        };
    }

    /**
     * {@code NOT_FOUND} is only actionable for a still-unresolved local payment (Invariant 8): the
     * provider having no record of a request CommerceCore is still waiting on is genuinely
     * unresolved, not proof of failure, so the case stays {@code OPEN} and reconciliation may be
     * retried later. For an already-terminal local payment, a provider not recognizing the request
     * isn't actionable here — the existing case (if any) is returned untouched.
     */
    private Optional<PaymentReconciliationCase> handleNotFound(Payment payment, Instant now) {
        return switch (payment.getStatus()) {
            case PENDING, UNKNOWN -> Optional.of(upsertCase(payment.getId(), ReconciliationStatus.OPEN,
                ProviderPaymentStatus.NOT_FOUND, ReconciliationReason.PROVIDER_NOT_FOUND, now));
            case AUTHORIZED, FAILED -> caseRepository.findByPaymentId(payment.getId());
        };
    }

    private PaymentReconciliationCase handleProviderAuthorized(Payment payment, ProviderLookupResult lookup,
        Instant now) {
        return switch (payment.getStatus()) {
            case PENDING, UNKNOWN -> {
                paymentTransitionService.resolveToAuthorized(payment.getId(), payment.getOrderId(),
                    payment.getAmount(), lookup.providerReference());
                // Provider truth is recorded either way — the customer really did pay — but
                // whether CommerceCore can safely act on it (confirm the order) depends on
                // whether the reservation this order made is still intact (Invariant 7).
                boolean ownershipIntact = reservationService.isOwnershipIntactForOrder(payment.getOrderId());
                yield ownershipIntact
                    ? upsertCase(payment.getId(), ReconciliationStatus.RESOLVED, ProviderPaymentStatus.AUTHORIZED,
                        null, now)
                    : upsertCase(payment.getId(), ReconciliationStatus.REQUIRES_REVIEW,
                        ProviderPaymentStatus.AUTHORIZED, ReconciliationReason.AUTHORIZED_WITHOUT_RESERVED_STOCK, now);
            }
            case AUTHORIZED -> Objects.equals(payment.getProviderReference(), lookup.providerReference())
                ? upsertCase(payment.getId(), ReconciliationStatus.RESOLVED, ProviderPaymentStatus.AUTHORIZED, null,
                    now)
                : upsertCase(payment.getId(), ReconciliationStatus.REQUIRES_REVIEW, ProviderPaymentStatus.AUTHORIZED,
                    ReconciliationReason.PROVIDER_REFERENCE_MISMATCH, now);
            case FAILED -> upsertCase(payment.getId(), ReconciliationStatus.REQUIRES_REVIEW,
                ProviderPaymentStatus.AUTHORIZED, ReconciliationReason.LOCAL_FAILED_PROVIDER_AUTHORIZED, now);
        };
    }

    private PaymentReconciliationCase handleProviderDeclined(Payment payment, Instant now) {
        return switch (payment.getStatus()) {
            case PENDING, UNKNOWN -> {
                paymentTransitionService.resolveToFailed(payment.getId(), payment.getOrderId(), payment.getAmount());
                yield upsertCase(payment.getId(), ReconciliationStatus.RESOLVED, ProviderPaymentStatus.DECLINED, null,
                    now);
            }
            case FAILED -> upsertCase(payment.getId(), ReconciliationStatus.RESOLVED, ProviderPaymentStatus.DECLINED,
                null, now);
            case AUTHORIZED -> upsertCase(payment.getId(), ReconciliationStatus.REQUIRES_REVIEW,
                ProviderPaymentStatus.DECLINED, ReconciliationReason.LOCAL_AUTHORIZED_PROVIDER_DECLINED, now);
        };
    }

    private PaymentReconciliationCase upsertCase(UUID paymentId, ReconciliationStatus status,
        ProviderPaymentStatus providerStatus, ReconciliationReason reason, Instant now) {
        Instant resolvedAt = status == ReconciliationStatus.OPEN ? null : now;
        caseRepository.upsert(UUID.randomUUID(), paymentId, status.name(), providerStatus.name(),
            reason == null ? null : reason.name(), now, resolvedAt);
        return caseRepository.findByPaymentId(paymentId)
            .orElseThrow(() -> new IllegalStateException("reconciliation case must exist immediately after upsert"));
    }
}

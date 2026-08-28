package com.commercecore.payment;

import com.commercecore.shared.BusinessRuleViolation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Reconciliation queries the provider; it never authorizes again — see
 * {@code docs/reconciliation.md}. Deliberately <strong>not</strong> {@code @Transactional}, the
 * same reasoning as {@link PaymentService}: {@link PaymentProvider#lookup} is an external call, so
 * no database transaction (and no row lock) is held open across it. The actual state mutation
 * happens in {@link PaymentReconciliationApplier#apply}, a separate short transaction that
 * re-reads the payment under lock — because the payment's local state may have changed (a webhook
 * arriving, another reconciliation attempt) while the provider was being queried.
 */
@Service
public class PaymentReconciliationService {

    private static final int DEFAULT_BATCH_LIMIT = 25;

    private final PaymentRepository paymentRepository;
    private final PaymentProvider paymentProvider;
    private final PaymentReconciliationApplier applier;

    public PaymentReconciliationService(PaymentRepository paymentRepository, PaymentProvider paymentProvider,
        PaymentReconciliationApplier applier) {
        this.paymentRepository = paymentRepository;
        this.paymentProvider = paymentProvider;
        this.applier = applier;
    }

    /**
     * {@code providerRequestId} is CommerceCore's own payment UUID — the same stable identity
     * {@link PaymentService#initiatePayment} already uses to call {@code authorize}, so the
     * provider can recognize a lookup for a request it actually received (Invariant 2).
     */
    public Optional<PaymentReconciliationCase> reconcile(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new BusinessRuleViolation(HttpStatus.NOT_FOUND, "payment_not_found",
                "Unknown payment: " + paymentId));

        ProviderLookupResult lookup = paymentProvider.lookup(payment.getId());

        return applier.apply(payment.getId(), lookup);
    }

    public record BatchResult(List<UUID> attempted, List<UUID> failed) {
    }

    /**
     * Bounded, not a full-table scan (Invariant: never load every payment into memory). Each
     * payment is reconciled independently — one payment's provider/database failure does not
     * abort or roll back any other payment's reconciliation in the same batch.
     */
    public BatchResult reconcileUnknownBatch(int limit) {
        List<Payment> candidates =
            paymentRepository.findByStatusOrderByCreatedAtAsc(PaymentStatus.UNKNOWN, PageRequest.of(0, limit));

        List<UUID> attempted = new ArrayList<>();
        List<UUID> failed = new ArrayList<>();
        for (Payment candidate : candidates) {
            attempted.add(candidate.getId());
            try {
                reconcile(candidate.getId());
            } catch (RuntimeException e) {
                failed.add(candidate.getId());
            }
        }
        return new BatchResult(attempted, failed);
    }

    public static int defaultBatchLimit() {
        return DEFAULT_BATCH_LIMIT;
    }
}

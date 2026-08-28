package com.commercecore.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A pure read model, like {@link Payment}: the only write is the atomic upsert in
 * {@link PaymentReconciliationCaseRepository#upsert}, always inside the same transaction as
 * whatever payment transition (if any) it accompanies. One row per payment
 * ({@code UNIQUE(payment_id)}) — not a history table.
 */
@Entity
@Table(name = "payment_reconciliation_cases")
public class PaymentReconciliationCase {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false, unique = true)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReconciliationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_provider_status")
    private ProviderPaymentStatus lastProviderStatus;

    @Enumerated(EnumType.STRING)
    @Column
    private ReconciliationReason reason;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected PaymentReconciliationCase() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public ReconciliationStatus getStatus() {
        return status;
    }

    public ProviderPaymentStatus getLastProviderStatus() {
        return lastProviderStatus;
    }

    public ReconciliationReason getReason() {
        return reason;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}

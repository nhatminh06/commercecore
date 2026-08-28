package com.commercecore.payment;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentReconciliationCaseRepository extends JpaRepository<PaymentReconciliationCase, UUID> {

    Optional<PaymentReconciliationCase> findByPaymentId(UUID paymentId);

    /**
     * One case per payment ({@code UNIQUE(payment_id)}), created on first reconciliation and
     * re-affirmed (never duplicated) on every later call — {@code attempt_count} increments each
     * time, {@code status}/{@code last_provider_status}/{@code reason}/{@code resolved_at} are
     * overwritten with this call's conclusion. No {@code @Transactional} here: always called from
     * within {@link PaymentReconciliationApplier}'s or {@code OrderPaymentWorkflowService}'s own
     * already-active transaction, so it commits or rolls back with whatever payment/order mutation
     * accompanies it — never on its own (see Invariant 26 in {@code docs/reconciliation.md}).
     */
    @Modifying
    @Query(value = """
        INSERT INTO payment_reconciliation_cases
            (id, payment_id, status, last_provider_status, reason, attempt_count, last_checked_at, created_at, resolved_at)
        VALUES
            (:id, :paymentId, :status, :lastProviderStatus, :reason, 1, :now, :now, :resolvedAt)
        ON CONFLICT (payment_id) DO UPDATE SET
            status = EXCLUDED.status,
            last_provider_status = EXCLUDED.last_provider_status,
            reason = EXCLUDED.reason,
            attempt_count = payment_reconciliation_cases.attempt_count + 1,
            last_checked_at = EXCLUDED.last_checked_at,
            resolved_at = EXCLUDED.resolved_at
        """, nativeQuery = true)
    void upsert(@Param("id") UUID id, @Param("paymentId") UUID paymentId, @Param("status") String status,
        @Param("lastProviderStatus") String lastProviderStatus, @Param("reason") String reason,
        @Param("now") Instant now, @Param("resolvedAt") Instant resolvedAt);
}

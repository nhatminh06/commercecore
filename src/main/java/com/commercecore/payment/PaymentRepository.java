package com.commercecore.payment;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByOrderId(UUID orderId);

    /**
     * Bounded by {@code pageable} — batch reconciliation ({@code PaymentReconciliationService
     * .reconcileUnknownBatch}) never loads every UNKNOWN payment into memory, only up to its
     * chosen limit.
     */
    List<Payment> findByStatusOrderByCreatedAtAsc(PaymentStatus status, Pageable pageable);

    /**
     * Serializes distinct webhook events racing against the same payment: event-ID uniqueness
     * alone only stops the same event ID from being processed twice, but two different event IDs
     * (evt_1, evt_2) can legitimately target the same payment concurrently. Holding this lock for
     * the rest of {@link PaymentWebhookService#processWebhook}'s transaction means a second event
     * for the same payment blocks until the first's transition has committed (or rolled back),
     * then re-reads the post-transition status — the same pattern already proven by
     * {@code ReservationRepository.findByIdForUpdate}. No {@code @Transactional} needed: always
     * called from within the caller's already-active transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") UUID id);

    /**
     * The entire concurrency mechanism for payment initiation: one atomic INSERT, guarded by the
     * {@code UNIQUE(order_id)} constraint via {@code ON CONFLICT DO NOTHING}. Whichever concurrent
     * caller's INSERT actually affects a row (returns 1) is the sole owner of this payment
     * attempt and the only one who may call the payment provider; every other caller (0 rows
     * affected — a row for this order already existed, whether freshly created a moment ago by a
     * concurrent racer or already resolved from an earlier call) must not call the provider and
     * simply reads back the current state instead. This is the same "affected-row-count decides
     * the winner" idiom as {@code InventoryRepository.tryConsume} and the cart upsert — no extra
     * IN_PROGRESS status or claim column needed. {@code @Transactional} here is required because
     * this is called directly from {@link PaymentService#initiatePayment}, which is deliberately
     * <em>not</em> transactional itself (it must not hold a DB transaction open across the
     * external provider call) — annotating the repository method itself gives this one statement
     * its own short transaction regardless of the caller's state.
     */
    @Transactional
    @Modifying
    @Query(value = """
        INSERT INTO payments (id, order_id, amount, status, created_at, updated_at)
        VALUES (:id, :orderId, :amount, 'PENDING', :now, :now)
        ON CONFLICT (order_id) DO NOTHING
        """, nativeQuery = true)
    int tryCreatePayment(@Param("id") UUID id, @Param("orderId") UUID orderId,
        @Param("amount") BigDecimal amount, @Param("now") Instant now);

    /**
     * No provider reference is passed: a genuine timeout means the response — and any reference
     * that would have been in it — never arrived. If a real provider integration ever exposed a
     * reference in some other ambiguous-but-partially-known case, this would take it as a
     * nullable parameter; there is no such case with this fake provider.
     */
    @Transactional
    @Modifying
    @Query(value = """
        UPDATE payments
        SET status = 'UNKNOWN', updated_at = :now
        WHERE id = :id AND status = 'PENDING'
        """, nativeQuery = true)
    int markUnknown(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * The only two statements that ever transition a payment to AUTHORIZED or FAILED — reused by
     * both {@code PaymentService.initiatePayment} (via {@link PaymentTransitionService}, its own
     * short transaction) and {@code PaymentWebhookService.processWebhook} (via the same
     * {@link PaymentTransitionService}, joining that method's already-active transaction).
     * Starting from either PENDING or UNKNOWN covers both the synchronous initiation path
     * (always PENDING at this point) and the webhook resolution path (PENDING or UNKNOWN) with
     * one statement. Never touches an already-AUTHORIZED/FAILED payment — whether a given call is
     * even legal at that point (e.g. a provider-reference match) is decided in Java by the
     * caller, under whatever lock it already holds, before this is invoked.
     *
     * <p>{@code clearAutomatically = true} matters when called from the webhook path
     * specifically: that caller already loaded the entity via {@link #findByIdForUpdate} earlier
     * in the same persistence context, and a native UPDATE doesn't refresh Hibernate's in-memory
     * copy of the row it just changed — without clearing, a subsequent {@code findById} in the
     * same transaction would return the stale pre-update instance instead of re-querying the
     * database.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE payments
        SET status = 'AUTHORIZED', provider_reference = :reference, updated_at = :now
        WHERE id = :id AND status IN ('PENDING', 'UNKNOWN')
        """, nativeQuery = true)
    int resolveToAuthorized(@Param("id") UUID id, @Param("reference") String reference, @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE payments
        SET status = 'FAILED', updated_at = :now
        WHERE id = :id AND status IN ('PENDING', 'UNKNOWN')
        """, nativeQuery = true)
    int resolveToFailed(@Param("id") UUID id, @Param("now") Instant now);
}

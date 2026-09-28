package com.commercecore.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findTop100ByOrderByCreatedAtDescIdDesc();

    /**
     * Called from within the caller's own already-active business transaction (checkout, a
     * payment transition) — deliberately no {@code @Transactional} here, so this statement
     * commits or rolls back exactly with the business fact it records, never on its own. That is
     * the entire transactional-outbox guarantee: if the order/payment write rolls back, this row
     * was never really inserted either.
     */
    @Modifying
    @Query(value = """
        INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, created_at, attempt_count)
        VALUES (:id, :aggregateType, :aggregateId, :eventType, CAST(:payload AS jsonb), :now, 0)
        """, nativeQuery = true)
    void insert(@Param("id") UUID id, @Param("aggregateType") String aggregateType, @Param("aggregateId") UUID aggregateId,
        @Param("eventType") String eventType, @Param("payload") String payload, @Param("now") Instant now);

    /**
     * Claims up to {@code batchSize} events for this worker in one atomic statement:
     * {@code FOR UPDATE SKIP LOCKED} means a row another transaction is concurrently claiming is
     * silently skipped rather than waited on, so concurrent publisher workers naturally partition
     * the pending backlog instead of serializing behind each other. A row is eligible whether it
     * has never been claimed ({@code claimed_at IS NULL}) or its previous claim is older than
     * {@code staleBefore} — a crashed worker's claim does not hold a row forever. {@code now} is
     * an explicit parameter (not read internally) precisely so claim/lease tests are
     * deterministic, the same reasoning as {@code ReservationService.expireDueReservations}.
     * {@code @Transactional} is required: the caller, {@link OutboxPublisher}, is deliberately
     * not transactional itself (it must not hold this transaction open across the external sink
     * call that happens after claiming).
     */
    @Transactional
    @Modifying
    @Query(value = """
        UPDATE outbox_events
        SET claim_token = :claimToken, claimed_at = :now, attempt_count = attempt_count + 1
        WHERE id IN (
            SELECT id FROM outbox_events
            WHERE published_at IS NULL
              AND (claimed_at IS NULL OR claimed_at <= :staleBefore)
            ORDER BY created_at, id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        )
        """, nativeQuery = true)
    int claimBatch(@Param("claimToken") UUID claimToken, @Param("now") Instant now,
        @Param("staleBefore") Instant staleBefore, @Param("batchSize") int batchSize);

    List<OutboxEvent> findByClaimTokenOrderByCreatedAtAscIdAsc(UUID claimToken);

    /**
     * Requires the caller's own claim token to match: a worker whose claim has already gone
     * stale and been reclaimed by someone else cannot mark the row published out from under the
     * new claimant, even if its own (slow) publish attempt eventually completes.
     */
    @Transactional
    @Modifying
    @Query(value = """
        UPDATE outbox_events
        SET published_at = :now, claim_token = NULL, claimed_at = NULL
        WHERE id = :id AND claim_token = :claimToken AND published_at IS NULL
        """, nativeQuery = true)
    int markPublished(@Param("id") UUID id, @Param("claimToken") UUID claimToken, @Param("now") Instant now);
}

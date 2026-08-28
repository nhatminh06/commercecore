package com.commercecore.kafka;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface KafkaEventReceiptRepository extends JpaRepository<KafkaEventReceipt, UUID> {

    /**
     * The entire consumer-side deduplication mechanism: one atomic INSERT guarded by
     * {@code PRIMARY KEY(event_id)} via {@code ON CONFLICT DO NOTHING} — the same
     * "affected-row-count decides new vs. duplicate" idiom used throughout this project (the
     * outbox claim, the payment webhook receipt). Whether this event ID has already been
     * consumed once or a thousand times, this statement is what actually decides "logical
     * processing" — the caller ({@link KafkaEventReceiptConsumer}) doesn't need to branch on the
     * result, because there is no business side effect yet: the receipt row itself is the proof.
     * {@code @Transactional} is required because the listener method that calls this is not
     * itself transactional (its offset acknowledgment must happen strictly after this commits,
     * not as part of the same transaction — see {@code docs/kafka.md}).
     */
    @Transactional
    @Modifying
    @Query(value = """
        INSERT INTO kafka_event_receipts (event_id, event_type, aggregate_type, aggregate_id, consumed_at)
        VALUES (:eventId, :eventType, :aggregateType, :aggregateId, :now)
        ON CONFLICT (event_id) DO NOTHING
        """, nativeQuery = true)
    int tryInsert(@Param("eventId") UUID eventId, @Param("eventType") String eventType,
        @Param("aggregateType") String aggregateType, @Param("aggregateId") UUID aggregateId,
        @Param("now") Instant now);
}

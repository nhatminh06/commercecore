package com.commercecore.kafka;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Proof that a given {@code eventId} has already been consumed — nothing more. A pure read
 * model, like the outbox tables: the only write is the atomic
 * {@link KafkaEventReceiptRepository#tryInsert}.
 */
@Entity
@Table(name = "kafka_event_receipts")
public class KafkaEventReceipt {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "consumed_at", nullable = false)
    private Instant consumedAt;

    protected KafkaEventReceipt() {
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }
}

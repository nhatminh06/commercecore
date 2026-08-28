package com.commercecore.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * The wire format published to {@link KafkaTopics#COMMERCE_EVENTS} — a direct mirror of
 * {@code com.commercecore.outbox.PublishedEvent}, not a new shape. {@code eventId} is always
 * {@code outbox_events.id}, preserved unchanged across every delivery attempt of the same
 * logical event; nothing here is outbox-internal bookkeeping (no claim token, no attempt count,
 * no publish timestamp).
 */
public record EventEnvelope(UUID eventId, String eventType, String aggregateType, UUID aggregateId,
    Instant createdAt, JsonNode payload) {
}

package com.commercecore.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * What the sink actually receives — deliberately narrower than {@link OutboxEvent}: no claim
 * token, no attempt count, no publish timestamp. Those are internal outbox bookkeeping, not
 * something a message consumer should ever see.
 */
public record PublishedEvent(UUID eventId, String eventType, String aggregateType, UUID aggregateId, String payload,
    Instant createdAt) {
}

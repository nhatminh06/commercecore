package com.commercecore.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EventSummaryResponse(
    UUID eventId,
    String eventType,
    String aggregateType,
    UUID aggregateId,
    UUID relatedOrderId,
    Instant createdAt,
    boolean published,
    Instant publishedAt,
    int publicationAttemptCount,
    List<EventInspectionResponse.ConsumerEvidence> consumers
) {
}

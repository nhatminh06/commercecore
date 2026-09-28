package com.commercecore.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EventInspectionResponse(
    UUID eventId,
    String eventType,
    String aggregateType,
    UUID aggregateId,
    UUID relatedOrderId,
    Instant createdAt,
    boolean published,
    Instant publishedAt,
    int publicationAttemptCount,
    JsonNode payload,
    List<ConsumerEvidence> consumers
) {

    public record ConsumerEvidence(
        String name,
        String purpose,
        boolean receiptExists,
        Instant processedAt,
        String effectEvidence
    ) {
    }
}

package com.commercecore.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one place an outbox row gets written. Every call site (checkout, payment transitions)
 * calls this instead of touching {@link OutboxEventRepository} directly, so it's always obvious
 * from the call itself which business fact just produced which event — not a generic dispatcher
 * that hides the mapping.
 */
@Component
public class OutboxEventWriter {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxEventWriter(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    public void write(AggregateType aggregateType, UUID aggregateId, OutboxEventType eventType, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox payload for " + eventType, e);
        }
        outboxEventRepository.insert(UUID.randomUUID(), aggregateType.name(), aggregateId, eventType.name(), json,
            Instant.now());
    }
}

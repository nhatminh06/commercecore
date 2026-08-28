package com.commercecore.kafka;

import com.commercecore.outbox.DomainEventSink;
import com.commercecore.outbox.PublishedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * The real {@link DomainEventSink} — active only under the {@code kafka} Spring profile (see
 * {@code docs/kafka.md} for why {@code RecordingEventSink} remains the default for everything
 * else, including all of Milestone 8's tests, unchanged).
 *
 * <p>{@code send(...).get(timeout)} turns Kafka's asynchronous send into a synchronous call with
 * a bounded wait: {@link com.commercecore.outbox.OutboxPublisher} only marks an event published
 * after this method returns normally, and any exception here — timeout, broker unavailable,
 * serialization failure — propagates as-is, leaving the outbox row unpublished and retryable.
 * This method never marks anything published itself; that decision belongs entirely to the
 * caller, exactly as {@code RecordingEventSink} already establishes.
 */
@Component
@Profile("kafka")
public class KafkaDomainEventSink implements DomainEventSink {

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final KafkaTemplate<String, EventEnvelope> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaDomainEventSink(KafkaTemplate<String, EventEnvelope> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(PublishedEvent event) {
        EventEnvelope envelope;
        try {
            JsonNode payloadNode = objectMapper.readTree(event.payload());
            envelope = new EventEnvelope(event.eventId(), event.eventType(), event.aggregateType(),
                event.aggregateId(), event.createdAt(), payloadNode);
        } catch (Exception e) {
            // A malformed outbox payload should never happen (it's written from controlled
            // domain records), but if it did, this must not be swallowed — the event stays
            // unpublished and inspectable rather than silently vanishing.
            throw new IllegalStateException("Failed to build Kafka envelope for event " + event.eventId(), e);
        }

        // Deterministic partition routing for one aggregate: every event about the same
        // order/payment shares a key and therefore a partition. This does not promise a global
        // order across different aggregates — see docs/kafka.md.
        String key = event.aggregateId().toString();

        try {
            kafkaTemplate.send(KafkaTopics.COMMERCE_EVENTS, key, envelope)
                .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while publishing event " + event.eventId() + " to Kafka", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish event " + event.eventId() + " to Kafka", e);
        }
    }
}

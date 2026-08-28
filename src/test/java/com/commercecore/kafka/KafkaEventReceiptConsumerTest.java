package com.commercecore.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Real Kafka broker (Testcontainers), real {@link KafkaEventReceiptConsumer} bean, real
 * {@code kafka_event_receipts} table. These tests never inspect Kafka records directly — they
 * publish envelopes straight through the autowired {@link KafkaTemplate} and observe the receipt
 * table, which is the only durable proof of "this consumer processed this event."
 */
class KafkaEventReceiptConsumerTest extends AbstractKafkaIntegrationTest {

    @Autowired
    private KafkaTemplate<String, EventEnvelope> kafkaTemplate;

    @Autowired
    private KafkaEventReceiptRepository receiptRepository;

    @Autowired
    private KafkaEventReceiptConsumer consumer;

    private record TestOrderPayload(UUID orderId, String status, BigDecimal total) {
    }

    @AfterEach
    void resetFailureHooks() {
        consumer.failBeforeReceipt(false);
        consumer.failBeforeAcknowledge(false);
    }

    private EventEnvelope newEnvelope() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        return new EventEnvelope(eventId, "ORDER_CREATED", "ORDER", aggregateId, java.time.Instant.now(),
            new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(
                new TestOrderPayload(aggregateId, "PENDING", new BigDecimal("10.00"))));
    }

    private void send(EventEnvelope envelope) throws Exception {
        kafkaTemplate.send(KafkaTopics.COMMERCE_EVENTS, envelope.aggregateId().toString(), envelope).get();
    }

    private boolean receiptExists(UUID eventId) {
        return receiptRepository.findById(eventId).isPresent();
    }

    @Test
    void oneEventProducesOneReceipt() throws Exception {
        EventEnvelope envelope = newEnvelope();
        send(envelope);

        awaitTrue(Duration.ofSeconds(20), () -> receiptExists(envelope.eventId()));

        KafkaEventReceipt receipt = receiptRepository.findById(envelope.eventId()).orElseThrow();
        assertThat(receipt.getEventType()).isEqualTo("ORDER_CREATED");
        assertThat(receipt.getAggregateType()).isEqualTo("ORDER");
        assertThat(receipt.getAggregateId()).isEqualTo(envelope.aggregateId());
    }

    @Test
    void duplicatePhysicalKafkaRecordsWithSameEventIdProduceOneReceipt() throws Exception {
        EventEnvelope envelope = newEnvelope();
        send(envelope);
        send(envelope);
        send(envelope);

        awaitTrue(Duration.ofSeconds(20), () -> receiptExists(envelope.eventId()));

        // Give any extra duplicate deliveries time to be (safely) reprocessed before counting.
        Thread.sleep(2000);
        long count = receiptRepository.findAll().stream()
            .filter(r -> r.getEventId().equals(envelope.eventId())).count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void distinctEventIdsProduceDistinctReceipts() throws Exception {
        EventEnvelope first = newEnvelope();
        EventEnvelope second = newEnvelope();
        send(first);
        send(second);

        awaitTrue(Duration.ofSeconds(20), () -> receiptExists(first.eventId()) && receiptExists(second.eventId()));

        assertThat(receiptRepository.findById(first.eventId())).isPresent();
        assertThat(receiptRepository.findById(second.eventId())).isPresent();
    }

    @Test
    void failureBeforeReceiptCommitAllowsRedeliveryToSucceed() throws Exception {
        EventEnvelope envelope = newEnvelope();
        consumer.failBeforeReceipt(true);

        send(envelope);

        // The listener throws every time while the hook is on; no receipt should ever appear.
        Thread.sleep(2000);
        assertThat(receiptExists(envelope.eventId())).isFalse();

        consumer.failBeforeReceipt(false);

        // The container's backoff redelivers the same record; once the hook is off, it succeeds.
        awaitTrue(Duration.ofSeconds(20), () -> receiptExists(envelope.eventId()));
    }

    @Test
    void failureAfterReceiptCommitBeforeAcknowledgeIsSafeOnRedelivery() throws Exception {
        EventEnvelope envelope = newEnvelope();
        consumer.failBeforeAcknowledge(true);

        send(envelope);

        // The receipt commits on the very first delivery attempt even though the listener then
        // throws (offset never acknowledged) - this proves the receipt-before-ack ordering.
        awaitTrue(Duration.ofSeconds(20), () -> receiptExists(envelope.eventId()));

        consumer.failBeforeAcknowledge(false);

        // Redelivery of the same record (offset was never committed) must not create a second
        // receipt or fail - the ON CONFLICT DO NOTHING makes reprocessing safe.
        Thread.sleep(3000);
        long count = receiptRepository.findAll().stream()
            .filter(r -> r.getEventId().equals(envelope.eventId())).count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void consumerGroupCollectivelyCoversFullEventSet() throws Exception {
        int eventCount = 30;
        java.util.List<EventEnvelope> envelopes = new java.util.ArrayList<>();
        for (int i = 0; i < eventCount; i++) {
            EventEnvelope envelope = newEnvelope();
            envelopes.add(envelope);
            send(envelope);
        }

        awaitTrue(Duration.ofSeconds(30),
            () -> envelopes.stream().allMatch(e -> receiptExists(e.eventId())));

        for (EventEnvelope envelope : envelopes) {
            assertThat(receiptRepository.findById(envelope.eventId())).isPresent();
        }
    }

    @Test
    void restartingConsumptionPreservesDeduplication() throws Exception {
        EventEnvelope envelope = newEnvelope();
        send(envelope);
        awaitTrue(Duration.ofSeconds(20), () -> receiptExists(envelope.eventId()));

        // Simulate what a restarted consumer would do on redelivery of an already-committed
        // event: call the same idempotent insert directly again.
        int affected = receiptRepository.tryInsert(envelope.eventId(), envelope.eventType(),
            envelope.aggregateType(), envelope.aggregateId(), java.time.Instant.now());

        assertThat(affected).isZero();
        long count = receiptRepository.findAll().stream()
            .filter(r -> r.getEventId().equals(envelope.eventId())).count();
        assertThat(count).isEqualTo(1);
    }
}

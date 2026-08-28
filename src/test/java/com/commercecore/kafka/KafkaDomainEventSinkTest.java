package com.commercecore.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercecore.outbox.OutboxEvent;
import com.commercecore.outbox.OutboxEventRepository;
import com.commercecore.outbox.OutboxEventType;
import com.commercecore.outbox.OutboxEventWriter;
import com.commercecore.outbox.OutboxPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real Kafka broker (Testcontainers), real {@link KafkaDomainEventSink} bean. Records are
 * inspected directly via a raw {@link KafkaConsumer} — never just "send() didn't throw."
 *
 * <p>{@link com.commercecore.AbstractIntegrationTest} shares one PostgreSQL container/database
 * across the entire suite, so other test classes (including checkout- and payment-heavy ones
 * added by later milestones) can leave a substantial backlog of never-explicitly-published outbox
 * rows behind. {@code OutboxPublisher}'s batch size is fixed and claims strictly in
 * {@code created_at} order, so a single {@code publishBatch} call is not guaranteed to reach a
 * brand-new event once that backlog exceeds the batch size.
 * {@link AbstractKafkaIntegrationTest#publishUntilPublished} repeats {@code publishBatch}
 * (advancing time past the claim lease each time) until our own event is actually published — the
 * same reasoning and pattern as {@code OutboxPublisherTest.publishUntil}.
 */
class KafkaDomainEventSinkTest extends AbstractKafkaIntegrationTest {

    @Autowired
    private OutboxEventWriter outboxEventWriter;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    private KafkaConsumer<String, String> rawConsumer;

    private record TestOrderPayload(UUID orderId, String status, java.math.BigDecimal total) {
    }

    private UUID writeOrderCreatedEvent() {
        UUID orderId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> outboxEventWriter.write(
            com.commercecore.outbox.AggregateType.ORDER, orderId, OutboxEventType.ORDER_CREATED,
            new TestOrderPayload(orderId, "PENDING", new java.math.BigDecimal("10.00"))));
        return outboxEventRepository.findAll().stream()
            .filter(e -> e.getAggregateId().equals(orderId))
            .findFirst().orElseThrow().getId();
    }

    private KafkaConsumer<String, String> newRawConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-inspector-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Set.of(KafkaTopics.COMMERCE_EVENTS));
        return consumer;
    }

    @AfterEach
    void closeConsumer() {
        if (rawConsumer != null) {
            rawConsumer.close();
        }
    }

    private ConsumerRecord<String, String> awaitRecordFor(KafkaConsumer<String, String> consumer, UUID eventId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (record.value().contains(eventId.toString())) {
                    return record;
                }
            }
        }
        throw new AssertionError("no Kafka record for event " + eventId + " within timeout");
    }

    @Test
    void successfulPublishSendsCorrectEnvelopeAndMarksOutboxPublished() throws Exception {
        UUID eventId = writeOrderCreatedEvent();
        rawConsumer = newRawConsumer();

        publishUntilPublished(outboxPublisher, outboxEventRepository, eventId, Instant.now());

        ConsumerRecord<String, String> record = awaitRecordFor(rawConsumer, eventId);
        assertThat(record.topic()).isEqualTo(KafkaTopics.COMMERCE_EVENTS);

        var envelope = objectMapper.readTree(record.value());
        assertThat(envelope.get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(envelope.get("eventType").asText()).isEqualTo("ORDER_CREATED");
        assertThat(envelope.get("aggregateType").asText()).isEqualTo("ORDER");
        String aggregateId = envelope.get("aggregateId").asText();
        assertThat(record.key()).isEqualTo(aggregateId);
        assertThat(envelope.get("payload").get("status").asText()).isEqualTo("PENDING");

        OutboxEvent stored = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(stored.getPublishedAt()).isNotNull();
    }

    @Test
    void alreadyPublishedEventIsNotSentAgain() throws Exception {
        UUID eventId = writeOrderCreatedEvent();
        rawConsumer = newRawConsumer();
        publishUntilPublished(outboxPublisher, outboxEventRepository, eventId, Instant.now());
        awaitRecordFor(rawConsumer, eventId);

        outboxPublisher.publishBatch(Instant.now().plusSeconds(200));

        // Drain a bit longer: no second record for this event ID should ever show up.
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        int countForEvent = 0;
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, String> records = rawConsumer.poll(Duration.ofMillis(300));
            for (ConsumerRecord<String, String> record : records) {
                if (record.value().contains(eventId.toString())) {
                    countForEvent++;
                }
            }
        }
        assertThat(countForEvent).isZero();
    }

    @Test
    void kafkaUnavailableLeavesOutboxUnpublishedAndLaterRecoveryPublishesTheSameEvent() {
        UUID eventId = writeOrderCreatedEvent();

        // A separate, plain (non-Spring-managed) sink pointed at an unreachable broker —
        // isolated from the shared Kafka Testcontainer other tests in this class depend on.
        Map<String, Object> badProps = new HashMap<>();
        badProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1");
        badProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        badProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        badProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3000);
        KafkaTemplate<String, EventEnvelope> badTemplate =
            new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(badProps));
        KafkaDomainEventSink unavailableSink = new KafkaDomainEventSink(badTemplate, objectMapper);
        OutboxPublisher publisherWithUnavailableKafka = new OutboxPublisher(outboxEventRepository, unavailableSink);

        publisherWithUnavailableKafka.publishBatch(Instant.now());

        OutboxEvent stillUnpublished = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(stillUnpublished.getPublishedAt()).isNull();

        // Recovery: the real, working publisher retries the SAME committed outbox row — no new
        // order/event was created to "fix" this.
        rawConsumer = newRawConsumer();
        publishUntilPublished(outboxPublisher, outboxEventRepository, eventId, Instant.now().plusSeconds(60));

        ConsumerRecord<String, String> record = awaitRecordFor(rawConsumer, eventId);
        assertThat(record.value()).contains(eventId.toString());
        assertThat(outboxEventRepository.findById(eventId).orElseThrow().getPublishedAt()).isNotNull();
    }

    @Test
    void concurrentPublishersWithRealKafkaPublishTheFullBacklog() throws Exception {
        int eventCount = 20;
        Set<UUID> eventIds = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < eventCount; i++) {
            eventIds.add(writeOrderCreatedEvent());
        }

        int workers = 4;
        Instant now = Instant.now();
        CyclicBarrier startingLine = new CyclicBarrier(workers);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch done = new CountDownLatch(workers);

        try {
            for (int i = 0; i < workers; i++) {
                executor.submit(() -> {
                    try {
                        startingLine.await();
                        for (int round = 0; round < 8; round++) {
                            outboxPublisher.publishBatch(now);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdown();
        }

        for (UUID eventId : eventIds) {
            assertThat(outboxEventRepository.findById(eventId).orElseThrow().getPublishedAt())
                .as("event %s must be published", eventId)
                .isNotNull();
        }
    }
}

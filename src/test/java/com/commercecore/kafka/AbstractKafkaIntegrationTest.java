package com.commercecore.kafka;

import com.commercecore.AbstractIntegrationTest;
import com.commercecore.outbox.OutboxEventRepository;
import com.commercecore.outbox.OutboxPublisher;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Adds a real Kafka broker (native KRaft mode, no ZooKeeper) on top of
 * {@link AbstractIntegrationTest}'s PostgreSQL container, and activates the {@code kafka}
 * profile so {@code KafkaDomainEventSink}/{@code KafkaEventReceiptConsumer} become the active
 * beans instead of {@code RecordingEventSink}. One broker, started once and shared across every
 * Kafka test class — the same singleton-container reasoning already used for PostgreSQL.
 */
@ActiveProfiles("kafka")
public abstract class AbstractKafkaIntegrationTest extends AbstractIntegrationTest {

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    /**
     * Kafka delivery/consumption is asynchronous. Bounded polling instead of a fixed sleep or an
     * extra test dependency (Awaitility) — never waits longer than {@code timeout}.
     */
    protected static void awaitTrue(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        if (!condition.getAsBoolean()) {
            throw new AssertionError("condition not met within " + timeout);
        }
    }

    /**
     * This whole class's static {@code POSTGRES}/{@code KAFKA} containers are shared across the
     * entire suite (see {@link AbstractIntegrationTest}), so other test classes routinely leave a
     * backlog of never-explicitly-published outbox rows behind. {@code OutboxPublisher} claims a
     * fixed-size batch strictly in {@code created_at} order, so a single {@code publishBatch} call
     * is not guaranteed to reach a brand-new event once that backlog exceeds the batch size.
     * Repeats {@code publishBatch} (advancing time past the claim lease each time) until our own
     * event is actually published — robust regardless of how large the shared backlog is.
     */
    protected static void publishUntilPublished(OutboxPublisher outboxPublisher,
        OutboxEventRepository outboxEventRepository, UUID eventId, Instant startAt) {
        Instant now = startAt;
        for (int i = 0; i < 20; i++) {
            outboxPublisher.publishBatch(now);
            if (outboxEventRepository.findById(eventId).orElseThrow().getPublishedAt() != null) {
                return;
            }
            now = now.plusSeconds(35);
        }
        throw new AssertionError("event " + eventId + " was not published after repeated publishBatch calls");
    }
}

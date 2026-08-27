package com.commercecore.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercecore.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Multiple publisher workers running concurrently must not lose events or double-claim an
 * actively-claimed row: {@code FOR UPDATE SKIP LOCKED} should let each worker naturally claim a
 * disjoint slice of the pending backlog.
 */
class OutboxPublisherConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxEventWriter outboxEventWriter;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private RecordingEventSink sink;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private record TestOrderPayload(UUID orderId, String status) {
    }

    private UUID writeEvent() {
        UUID aggregateId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> outboxEventWriter.write(
            AggregateType.ORDER, aggregateId, OutboxEventType.ORDER_CREATED,
            new TestOrderPayload(aggregateId, "PENDING")));
        return outboxEventRepository.findAll().stream()
            .filter(e -> e.getAggregateId().equals(aggregateId))
            .findFirst().orElseThrow().getId();
    }

    @Test
    void concurrentPublisherWorkersPublishAllPendingEventsExactlyOnceEach() throws Exception {
        sink.nextOutcome(RecordingEventSink.NextOutcome.SUCCESS);
        int eventCount = 30;
        Set<UUID> eventIds = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < eventCount; i++) {
            eventIds.add(writeEvent());
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
                        // Each worker sweeps repeatedly, as a real polling publisher would,
                        // since a single claimBatch call may not exhaust the whole backlog.
                        for (int round = 0; round < 10; round++) {
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
            assertThat(sink.deliveryCountFor(eventId))
                .as("event %s must be delivered exactly once under concurrent publishing", eventId)
                .isEqualTo(1);
            OutboxEvent event = outboxEventRepository.findById(eventId).orElseThrow();
            assertThat(event.getPublishedAt())
                .as("event %s must end up marked published", eventId)
                .isNotNull();
        }
    }

    @Test
    void concurrentPublishersNeverBothMarkTheSameEventPublished() throws Exception {
        sink.nextOutcome(RecordingEventSink.NextOutcome.SUCCESS);
        UUID eventId = writeEvent();
        Instant now = Instant.now();

        int attempts = 10;
        CyclicBarrier startingLine = new CyclicBarrier(attempts);
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch done = new CountDownLatch(attempts);

        try {
            for (int i = 0; i < attempts; i++) {
                executor.submit(() -> {
                    try {
                        startingLine.await();
                        outboxPublisher.publishBatch(now);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdown();
        }

        List<PublishedEvent> deliveries = sink.deliveries().stream()
            .filter(e -> e.eventId().equals(eventId))
            .toList();
        assertThat(deliveries).hasSize(1);
        assertThat(outboxEventRepository.findById(eventId).orElseThrow().getPublishedAt()).isNotNull();
    }
}

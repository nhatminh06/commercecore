package com.commercecore.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.commercecore.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sequential publisher behavior: successful publication, the two failure modes
 * ({@code FAIL_BEFORE_ACCEPT}, {@code ACCEPT_THEN_THROW}), event-ID stability across retries,
 * attempt-count tracking, and claim-lease/claim-token correctness. Concurrent publisher safety
 * is in {@link OutboxPublisherConcurrencyTest}.
 *
 * <p>{@link AbstractIntegrationTest} shares one PostgreSQL container/database across the entire
 * suite, so other test classes can leave unrelated unpublished events behind. Rather than assume
 * our event lands in the very next claimed batch, {@link #publishUntil} repeats
 * {@code publishBatch} (advancing time past the claim lease each time) until our own event
 * reaches the expected state — robust regardless of how large the shared backlog is.
 */
class OutboxPublisherTest extends AbstractIntegrationTest {

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

    // OutboxEventRepository.insert deliberately has no @Transactional of its own (see its doc
    // comment) — it always runs inside a caller's business transaction in production. Test setup
    // has to supply one explicitly, the same pattern used elsewhere for other @Modifying queries
    // that rely on an enclosing transaction.
    private UUID writeEvent() {
        UUID aggregateId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> outboxEventWriter.write(
            AggregateType.ORDER, aggregateId, OutboxEventType.ORDER_CREATED,
            new TestOrderPayload(aggregateId, "PENDING")));
        return outboxEventRepository.findAll().stream()
            .filter(e -> e.getAggregateId().equals(aggregateId))
            .findFirst().orElseThrow().getId();
    }

    private record TestOrderPayload(UUID orderId, String status) {
    }

    private void publishUntil(UUID eventId, Instant startAt, long minDeliveries) {
        Instant now = startAt;
        for (int i = 0; i < 20; i++) {
            outboxPublisher.publishBatch(now);
            if (sink.deliveryCountFor(eventId) >= minDeliveries) {
                return;
            }
            now = now.plusSeconds(35);
        }
        fail("event " + eventId + " did not reach " + minDeliveries + " deliveries after repeated publishBatch calls");
    }

    @Test
    void successfulPublicationMarksEventPublishedAndIsNotRepublished() {
        sink.nextOutcome(RecordingEventSink.NextOutcome.SUCCESS);
        UUID eventId = writeEvent();
        Instant now = Instant.now();

        publishUntil(eventId, now, 1);

        OutboxEvent event = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(sink.deliveryCountFor(eventId)).isEqualTo(1);

        // A later run must not touch an already-published event.
        outboxPublisher.publishBatch(now.plusSeconds(200));
        assertThat(sink.deliveryCountFor(eventId)).isEqualTo(1);
    }

    @Test
    void failBeforeAcceptLeavesEventUnpublishedAndRetryable() {
        sink.nextOutcome(RecordingEventSink.NextOutcome.FAIL_BEFORE_ACCEPT);
        UUID eventId = writeEvent();
        Instant now = Instant.now();

        outboxPublisher.publishBatch(now);

        OutboxEvent event = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(event.getPublishedAt()).isNull();
        assertThat(sink.deliveryCountFor(eventId)).isEqualTo(0);

        // Retry (after the claim lease elapses) succeeds.
        sink.nextOutcome(RecordingEventSink.NextOutcome.SUCCESS);
        publishUntil(eventId, now.plusSeconds(60), 1);

        assertThat(outboxEventRepository.findById(eventId).orElseThrow().getPublishedAt()).isNotNull();
        assertThat(sink.deliveryCountFor(eventId)).isEqualTo(1);
    }

    @Test
    void acceptThenThrowDeliversTwiceButKeepsTheSameEventId() {
        sink.nextOutcome(RecordingEventSink.NextOutcome.ACCEPT_THEN_THROW);
        UUID eventId = writeEvent();
        Instant now = Instant.now();

        outboxPublisher.publishBatch(now);

        // The sink recorded it (ground truth) even though the publisher saw a failure.
        assertThat(sink.deliveryCountFor(eventId)).isEqualTo(1);
        OutboxEvent afterFirstAttempt = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(afterFirstAttempt.getPublishedAt()).isNull();

        // Retry, after the lease elapses, delivers the SAME event ID again — this is the
        // unavoidable at-least-once duplicate this milestone proves rather than hides.
        sink.nextOutcome(RecordingEventSink.NextOutcome.SUCCESS);
        publishUntil(eventId, now.plusSeconds(60), 2);

        assertThat(sink.deliveryCountFor(eventId)).isEqualTo(2);
        List<PublishedEvent> deliveries = sink.deliveries().stream()
            .filter(e -> e.eventId().equals(eventId))
            .toList();
        assertThat(deliveries).hasSize(2);
        assertThat(deliveries.get(0).eventId()).isEqualTo(deliveries.get(1).eventId());
        assertThat(outboxEventRepository.findById(eventId).orElseThrow().getPublishedAt()).isNotNull();
    }

    @Test
    void attemptCountIncrementsOnEachClaim() {
        sink.nextOutcome(RecordingEventSink.NextOutcome.FAIL_BEFORE_ACCEPT);
        UUID eventId = writeEvent();
        Instant now = Instant.now();

        // A large batch size guarantees our brand-new (never-claimed) event is included
        // regardless of how much unrelated backlog other tests left behind.
        outboxEventRepository.claimBatch(UUID.randomUUID(), now, now.minusSeconds(30), 10_000);
        assertThat(outboxEventRepository.findById(eventId).orElseThrow().getAttemptCount()).isEqualTo(1);

        outboxEventRepository.claimBatch(UUID.randomUUID(), now.plusSeconds(60), now.plusSeconds(30), 10_000);
        assertThat(outboxEventRepository.findById(eventId).orElseThrow().getAttemptCount()).isEqualTo(2);
    }

    @Test
    void activeClaimIsNotStolenByAnEarlierRunBeforeTheLeaseElapses() {
        UUID eventId = writeEvent();
        Instant now = Instant.now();

        int firstClaim = outboxEventRepository.claimBatch(UUID.randomUUID(), now, now.minusSeconds(30), 10_000);
        assertThat(firstClaim).isGreaterThanOrEqualTo(1);
        int attemptsAfterFirst = outboxEventRepository.findById(eventId).orElseThrow().getAttemptCount();

        // Same instant again: the claim from a moment ago has not gone stale yet, so a second
        // claim attempt at the same "now" must not re-claim our event.
        outboxEventRepository.claimBatch(UUID.randomUUID(), now, now.minusSeconds(30), 10_000);

        assertThat(outboxEventRepository.findById(eventId).orElseThrow().getAttemptCount())
            .isEqualTo(attemptsAfterFirst);
    }

    @Test
    void wrongClaimTokenCannotMarkAnotherWorkersEventPublished() {
        UUID eventId = writeEvent();
        Instant now = Instant.now();
        UUID realClaimToken = UUID.randomUUID();

        outboxEventRepository.claimBatch(realClaimToken, now, now.minusSeconds(30), 10_000);

        int updated = outboxEventRepository.markPublished(eventId, UUID.randomUUID(), Instant.now());

        assertThat(updated).isZero();
        assertThat(outboxEventRepository.findById(eventId).orElseThrow().getPublishedAt()).isNull();
    }
}

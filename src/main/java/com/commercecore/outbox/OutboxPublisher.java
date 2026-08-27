package com.commercecore.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Deliberately <strong>not</strong> {@code @Transactional} — the same reasoning as
 * {@link com.commercecore.payment.PaymentService}: claiming, the external sink call, and marking
 * published are three separate steps precisely so the sink call never happens with a database
 * transaction (and its row locks) held open. See {@code docs/outbox.md}.
 *
 * <ol>
 *   <li><strong>Claim</strong> — {@link OutboxEventRepository#claimBatch}, its own short
 *       transaction, commits before the sink is ever touched.
 *   <li><strong>Publish</strong> — {@link DomainEventSink#publish}, no transaction open. If this
 *       throws, this publisher cannot tell whether the sink actually accepted the message before
 *       failing — see invariant 6 in {@code docs/outbox.md}. Either way the event is simply left
 *       claimed, not marked published, and becomes eligible for retry once the claim lease
 *       expires.
 *   <li><strong>Mark published</strong> — {@link OutboxEventRepository#markPublished}, another
 *       short transaction, only for events the sink call actually returned from successfully.
 * </ol>
 */
@Component
public class OutboxPublisher {

    private static final int BATCH_SIZE = 25;
    private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);

    private final OutboxEventRepository outboxEventRepository;
    private final DomainEventSink sink;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository, DomainEventSink sink) {
        this.outboxEventRepository = outboxEventRepository;
        this.sink = sink;
    }

    public PublishResult publishBatch(Instant now) {
        UUID claimToken = UUID.randomUUID();
        Instant staleBefore = now.minus(CLAIM_LEASE);
        int claimed = outboxEventRepository.claimBatch(claimToken, now, staleBefore, BATCH_SIZE);
        if (claimed == 0) {
            return new PublishResult(0, 0);
        }

        List<OutboxEvent> events = outboxEventRepository.findByClaimTokenOrderByCreatedAtAscIdAsc(claimToken);

        int published = 0;
        for (OutboxEvent event : events) {
            try {
                sink.publish(new PublishedEvent(event.getId(), event.getEventType().name(),
                    event.getAggregateType().name(), event.getAggregateId(), event.getPayload(), event.getCreatedAt()));
            } catch (RuntimeException e) {
                // Cannot distinguish "never arrived" from "arrived but the ack was lost" — leave
                // it claimed; the lease expiring is what makes it retryable again.
                continue;
            }
            outboxEventRepository.markPublished(event.getId(), claimToken, Instant.now());
            published++;
        }

        return new PublishResult(claimed, published);
    }
}

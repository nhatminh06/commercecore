package com.commercecore.outbox;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The deterministic fake sink used everywhere except the {@code kafka} Spring profile — a real
 * Spring bean (not a test mock), configured directly by tests and {@link DevOutboxController}.
 * Its next outcome is set explicitly, never random, mirroring {@code FakePaymentProvider}'s
 * established pattern — including the same "ground truth despite the caller seeing failure"
 * idiom for {@code ACCEPT_THEN_THROW}.
 *
 * <p>{@code @Profile("!kafka")}: active by default (every existing test and this project's
 * baseline behavior are unaffected), deactivated only when the real
 * {@code com.commercecore.kafka.KafkaDomainEventSink} takes over — see {@code docs/kafka.md}.
 * Exactly one {@link DomainEventSink} bean exists in any given context, never both.
 */
@Component
@Profile("!kafka")
public class RecordingEventSink implements DomainEventSink {

    public enum NextOutcome {
        SUCCESS,
        FAIL_BEFORE_ACCEPT,
        ACCEPT_THEN_THROW
    }

    private volatile NextOutcome nextOutcome = NextOutcome.SUCCESS;
    private final List<PublishedEvent> deliveries = new CopyOnWriteArrayList<>();

    public void nextOutcome(NextOutcome outcome) {
        this.nextOutcome = outcome;
    }

    public List<PublishedEvent> deliveries() {
        return List.copyOf(deliveries);
    }

    public long deliveryCountFor(UUID eventId) {
        return deliveries.stream().filter(e -> e.eventId().equals(eventId)).count();
    }

    @Override
    public void publish(PublishedEvent event) {
        switch (nextOutcome) {
            case SUCCESS -> deliveries.add(event);
            case FAIL_BEFORE_ACCEPT -> throw new RuntimeException(
                "simulated sink failure: the message was never accepted");
            case ACCEPT_THEN_THROW -> {
                // The sink DID record it — this is the milestone's central failure window: the
                // caller is about to see an exception and has no way to know the message was
                // actually accepted.
                deliveries.add(event);
                throw new RuntimeException(
                    "simulated sink failure: accepted, but the acknowledgment was lost");
            }
        }
    }
}

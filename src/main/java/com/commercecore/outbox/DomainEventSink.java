package com.commercecore.outbox;

/**
 * The future message broker's boundary — genuinely external, so a real interface earns its
 * place here (unlike most of this codebase's single-implementation classes). No Kafka yet: see
 * {@link RecordingEventSink} and {@code docs/outbox.md}.
 */
public interface DomainEventSink {

    /**
     * @throws RuntimeException if the sink cannot prove the event was accepted — the caller
     *     ({@link OutboxPublisher}) treats any exception here identically, because it genuinely
     *     cannot distinguish "never arrived" from "arrived but the acknowledgment was lost."
     */
    void publish(PublishedEvent event);
}

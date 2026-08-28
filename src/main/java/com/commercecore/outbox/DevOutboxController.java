package com.commercecore.outbox;

import java.time.Instant;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Development-only trigger — not a real production API. No automatic scheduling exists;
 * publication is invoked explicitly by tests or this endpoint, regardless of which
 * {@link DomainEventSink} is active. See {@link DevRecordingSinkController} for the
 * {@code RecordingEventSink}-specific control surface, which only exists outside the
 * {@code kafka} profile.
 */
@RestController
@RequestMapping("/api/dev/outbox")
public class DevOutboxController {

    private final OutboxPublisher outboxPublisher;

    public DevOutboxController(OutboxPublisher outboxPublisher) {
        this.outboxPublisher = outboxPublisher;
    }

    @PostMapping("/publish")
    public PublishResult publish() {
        return outboxPublisher.publishBatch(Instant.now());
    }
}

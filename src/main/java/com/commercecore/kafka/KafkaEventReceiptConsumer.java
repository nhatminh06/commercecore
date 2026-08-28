package com.commercecore.kafka;

import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * A technical proof consumer only. It never confirms an order, releases a reservation, changes
 * payment state, or performs any business workflow — it exists purely to demonstrate that Kafka
 * delivery, consumer-group semantics, and redelivery are all handled correctly by persisting a
 * deduplication receipt. Business consumption is a later milestone.
 *
 * <p>Ordering is deliberate: the receipt INSERT commits (or safely no-ops for a duplicate) before
 * {@code acknowledgment.acknowledge()} is ever called. If this method throws before reaching the
 * acknowledgment, the container's error handler leaves the offset uncommitted and redelivers the
 * same record — never silently losing it. See {@code docs/kafka.md}.
 */
@Component
@Profile("kafka")
public class KafkaEventReceiptConsumer {

    private final KafkaEventReceiptRepository receiptRepository;

    // Test-only failure injection, defaulted off and never toggled outside tests — the same
    // "real bean, explicitly controllable outcome" pattern already established by
    // FakePaymentProvider.nextOutcome and RecordingEventSink.nextOutcome, applied to the
    // consumer side so tests can deterministically prove redelivery behavior without a generic
    // chaos-injection framework.
    private volatile boolean failBeforeReceipt = false;
    private volatile boolean failBeforeAcknowledge = false;

    public KafkaEventReceiptConsumer(KafkaEventReceiptRepository receiptRepository) {
        this.receiptRepository = receiptRepository;
    }

    public void failBeforeReceipt(boolean fail) {
        this.failBeforeReceipt = fail;
    }

    public void failBeforeAcknowledge(boolean fail) {
        this.failBeforeAcknowledge = fail;
    }

    @KafkaListener(topics = KafkaTopics.COMMERCE_EVENTS, groupId = "${spring.kafka.consumer.group-id}",
        concurrency = "3")
    public void onMessage(EventEnvelope envelope, Acknowledgment acknowledgment) {
        if (failBeforeReceipt) {
            throw new RuntimeException("test-injected failure before the receipt row commits");
        }

        receiptRepository.tryInsert(envelope.eventId(), envelope.eventType(), envelope.aggregateType(),
            envelope.aggregateId(), Instant.now());

        if (failBeforeAcknowledge) {
            throw new RuntimeException("test-injected failure after the receipt committed, before the offset ack");
        }

        acknowledgment.acknowledge();
    }
}

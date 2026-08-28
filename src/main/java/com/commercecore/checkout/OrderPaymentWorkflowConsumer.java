package com.commercecore.checkout;

import com.commercecore.kafka.EventEnvelope;
import com.commercecore.kafka.KafkaTopics;
import com.commercecore.outbox.OutboxEventType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * The only Kafka input to the order workflow: {@code PAYMENT_AUTHORIZED} and
 * {@code PAYMENT_FAILED}. Everything else on {@code commerce.events} — including this workflow's
 * own {@code ORDER_CONFIRMED}/{@code ORDER_CANCELLED} output — is explicitly irrelevant here and
 * is acknowledged without any database work, so an irrelevant record never blocks the partition
 * and {@code ORDER_CONFIRMED} can never recursively trigger another {@code ORDER_CONFIRMED} (see
 * {@code docs/order-workflow.md}).
 *
 * <p>A separate consumer group ({@link #GROUP_ID}) from the technical proof consumer
 * ({@code commercecore-proof-consumer}) — the two independently receive and process every record
 * on the topic. This listener itself contains no business logic; it only parses the envelope and
 * hands off to {@link OrderPaymentWorkflowService}, which owns the actual transaction. The offset
 * is acknowledged only after that transaction returns normally — a transaction that throws (a
 * malformed payload, a payment-state mismatch, an invariant conflict) leaves the offset
 * unacknowledged, so Kafka can redeliver the same record.
 */
@Component
@Profile("kafka")
public class OrderPaymentWorkflowConsumer {

    public static final String GROUP_ID = "commercecore-order-workflow";

    private final OrderPaymentWorkflowService workflowService;

    // Test-only failure injection, defaulted off — the same "real bean, explicitly controllable
    // outcome" pattern as KafkaEventReceiptConsumer.failBeforeAcknowledge, applied here so a test
    // can prove that a crash after the workflow transaction commits but before the Kafka offset
    // is acknowledged is safe on redelivery (the receipt already exists; see docs/order-workflow.md).
    private volatile boolean failAfterCommitBeforeAcknowledge = false;

    public OrderPaymentWorkflowConsumer(OrderPaymentWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    public void failAfterCommitBeforeAcknowledge(boolean fail) {
        this.failAfterCommitBeforeAcknowledge = fail;
    }

    @KafkaListener(topics = KafkaTopics.COMMERCE_EVENTS, groupId = GROUP_ID, concurrency = "3")
    public void onMessage(EventEnvelope envelope, Acknowledgment acknowledgment) {
        if (envelope.eventType().equals(OutboxEventType.PAYMENT_AUTHORIZED.name())) {
            workflowService.handlePaymentAuthorized(envelope.eventId(), envelope.aggregateId(),
                requireOrderId(envelope));
        } else if (envelope.eventType().equals(OutboxEventType.PAYMENT_FAILED.name())) {
            workflowService.handlePaymentFailed(envelope.eventId(), envelope.aggregateId(), requireOrderId(envelope));
        }
        // ORDER_CREATED / ORDER_CONFIRMED / ORDER_CANCELLED are not workflow inputs — safely
        // acknowledged with no database work, never re-processed as if they were new payment facts.

        if (failAfterCommitBeforeAcknowledge) {
            throw new RuntimeException(
                "test-injected failure after the workflow transaction committed, before the offset ack");
        }
        acknowledgment.acknowledge();
    }

    private static UUID requireOrderId(EventEnvelope envelope) {
        JsonNode orderIdNode = envelope.payload().get("orderId");
        if (orderIdNode == null || orderIdNode.isNull() || orderIdNode.asText().isBlank()) {
            throw new IllegalArgumentException(
                "Malformed " + envelope.eventType() + " payload for event " + envelope.eventId()
                    + ": missing orderId");
        }
        try {
            return UUID.fromString(orderIdNode.asText());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Malformed " + envelope.eventType() + " payload for event " + envelope.eventId()
                    + ": orderId is not a valid UUID",
                e);
        }
    }
}

package com.commercecore.checkout;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Proof that {@code OrderPaymentWorkflowService} has already processed a given payment event ID
 * — nothing more, same shape and purpose as {@code kafka.KafkaEventReceipt}, but deliberately a
 * separate table (see V10 and {@code docs/order-workflow.md}): deduplication identity is scoped
 * to a consumer's own side effect, not globally to the Kafka topic. The technical proof consumer
 * and this workflow consumer are different Kafka consumer groups and must each be free to process
 * the same event ID once for their own purpose.
 */
@Entity
@Table(name = "order_workflow_event_receipts")
public class OrderWorkflowEventReceipt {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected OrderWorkflowEventReceipt() {
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}

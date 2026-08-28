package com.commercecore.checkout;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderWorkflowEventReceiptRepository extends JpaRepository<OrderWorkflowEventReceipt, UUID> {

    /**
     * The entire event-ID deduplication mechanism for the order workflow consumer: one atomic
     * INSERT guarded by {@code PRIMARY KEY(event_id)} via {@code ON CONFLICT DO NOTHING} — the
     * same affected-row-count idiom already used throughout this project. No {@code @Transactional}
     * here: unlike {@code KafkaEventReceiptRepository.tryInsert} (called from a non-transactional
     * listener), this is always called from within {@code OrderPaymentWorkflowService}'s own
     * already-active {@code @Transactional} method, so it simply joins that transaction — the
     * receipt, the business mutation, and the resulting outbox event all commit or roll back
     * together.
     */
    @Modifying
    @Query(value = """
        INSERT INTO order_workflow_event_receipts (event_id, event_type, payment_id, order_id, processed_at)
        VALUES (:eventId, :eventType, :paymentId, :orderId, :now)
        ON CONFLICT (event_id) DO NOTHING
        """, nativeQuery = true)
    int tryInsert(@Param("eventId") UUID eventId, @Param("eventType") String eventType,
        @Param("paymentId") UUID paymentId, @Param("orderId") UUID orderId, @Param("now") Instant now);
}

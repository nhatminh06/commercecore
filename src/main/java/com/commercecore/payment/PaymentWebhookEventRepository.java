package com.commercecore.payment;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEvent, String> {

    /**
     * The entire duplicate-delivery mechanism: one atomic INSERT guarded by
     * {@code PRIMARY KEY(provider_event_id)} via {@code ON CONFLICT DO NOTHING}. Whichever
     * delivery's INSERT actually lands (1 row) is the one that gets to apply a payment
     * transition; every other delivery of the same event ID (0 rows — a receipt already exists)
     * must not transition the payment again. No {@code @Transactional} here: this is only ever
     * called from {@link PaymentWebhookService#processWebhook}, which is itself
     * {@code @Transactional} — unlike payment initiation, webhook processing makes no external
     * call, so it doesn't need the phase-split that method does, and this statement simply joins
     * the already-active transaction.
     */
    @Modifying
    @Query(value = """
        INSERT INTO payment_webhook_events (provider_event_id, payment_id, event_type, provider_reference, received_at)
        VALUES (:eventId, :paymentId, :eventType, :providerReference, :now)
        ON CONFLICT (provider_event_id) DO NOTHING
        """, nativeQuery = true)
    int tryClaimEvent(@Param("eventId") String eventId, @Param("paymentId") UUID paymentId,
        @Param("eventType") String eventType, @Param("providerReference") String providerReference,
        @Param("now") Instant now);
}

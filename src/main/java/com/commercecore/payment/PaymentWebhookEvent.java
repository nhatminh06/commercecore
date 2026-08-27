package com.commercecore.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A durable receipt proving CommerceCore has already seen this exact provider event —
 * {@code provider_event_id} is the provider's own event identity, a different thing entirely
 * from {@code payment_id} (CommerceCore's payment record) or a provider reference (the
 * provider's charge/authorization identity). Pure read model, like {@link Payment}: the only
 * write is the atomic claim in {@link PaymentWebhookEventRepository#tryClaimEvent}.
 */
@Entity
@Table(name = "payment_webhook_events")
public class PaymentWebhookEvent {

    @Id
    @Column(name = "provider_event_id")
    private String providerEventId;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private WebhookEventType eventType;

    @Column(name = "provider_reference")
    private String providerReference;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected PaymentWebhookEvent() {
    }

    public String getProviderEventId() {
        return providerEventId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public WebhookEventType getEventType() {
        return eventType;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}

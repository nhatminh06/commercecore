package com.commercecore.checkout;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted identity for one logical checkout request: {@code idempotency_key -> (cart, order)}.
 * A row is only ever written after checkout has fully succeeded and committed — there is no
 * IN_PROGRESS state, because the advisory lock in {@link CheckoutIdempotencyRepository} already
 * guarantees only one transaction can be deciding "does this key exist yet?" for a given key at
 * a time. No response body, HTTP status, or generic request fingerprint is stored: checkout's
 * only input is {@code cartId}, so that's the entire request identity that matters here.
 */
@Entity
@Table(name = "checkout_idempotency")
public class CheckoutIdempotency {

    @Id
    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "cart_id", nullable = false)
    private UUID cartId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CheckoutIdempotency() {
    }

    public CheckoutIdempotency(String idempotencyKey, UUID cartId, UUID orderId, Instant createdAt) {
        this.idempotencyKey = idempotencyKey;
        this.cartId = cartId;
        this.orderId = orderId;
        this.createdAt = createdAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getCartId() {
        return cartId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

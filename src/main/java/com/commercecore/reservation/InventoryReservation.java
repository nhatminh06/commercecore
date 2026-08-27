package com.commercecore.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * An ACTIVE reservation has already removed its quantity from
 * {@code inventory.available_quantity} — unlike a cart line, which never does. Legal transitions
 * (ACTIVE -> CONFIRMED/RELEASED/EXPIRED, nothing out of a terminal state) are decided and applied
 * by {@link ReservationService}, not here: this entity is deliberately just state, not a
 * self-contained state machine.
 */
@Entity
@Table(name = "inventory_reservations")
public class InventoryReservation {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    // Null for reservations created through the standalone /api/reservations API. Set only for
    // reservations created by CheckoutService, associating them with the order that claimed the
    // stock.
    @Column(name = "order_id")
    private UUID orderId;

    protected InventoryReservation() {
    }

    public InventoryReservation(UUID id, String sku, int quantity, Instant expiresAt) {
        this.id = id;
        this.sku = sku;
        this.quantity = quantity;
        this.status = ReservationStatus.ACTIVE;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public String getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    void setStatus(ReservationStatus status) {
        this.status = status;
    }

    void setOrderId(UUID orderId) {
        this.orderId = orderId;
    }
}

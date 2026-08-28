package com.commercecore.checkout;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A historical checkout record. {@code total_amount} is computed once, before this row is ever
 * persisted, from the order's own lines, and never changes again. {@code status} is the one
 * mutable field, and only through {@link #setStatus}, package-private so only
 * {@code OrderPaymentWorkflowService} (same reasoning as {@code ReservationService} mutating
 * {@code InventoryReservation}) can drive it — never a public setter, and never a client-facing
 * PUT/PATCH endpoint.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Order() {
    }

    public Order(UUID id, OrderStatus status, BigDecimal totalAmount, Instant createdAt) {
        this.id = id;
        this.status = status;
        this.totalAmount = totalAmount;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    void setStatus(OrderStatus status) {
        this.status = status;
    }
}

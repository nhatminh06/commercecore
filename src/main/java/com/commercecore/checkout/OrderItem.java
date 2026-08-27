package com.commercecore.checkout;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A price snapshot, not a live reference: {@code unitPrice}/{@code lineTotal} are captured at
 * checkout time and never re-read from {@code products.price_amount} afterward, so a later
 * catalog price change cannot retroactively change a historical order's numbers. No setters —
 * immutable once created.
 */
@Entity
@Table(name = "order_items")
@IdClass(OrderItemId.class)
public class OrderItem {

    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Id
    @Column(name = "sku")
    private String sku;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "line_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineTotal;

    protected OrderItem() {
    }

    public OrderItem(UUID orderId, String sku, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
        this.orderId = orderId;
        this.sku = sku;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.lineTotal = lineTotal;
    }

    public String getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }
}

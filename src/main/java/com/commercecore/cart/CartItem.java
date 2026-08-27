package com.commercecore.cart;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Read model for a cart line. Writes go through
 * {@link CartItemRepository#setQuantity} (an atomic upsert), not through saving this entity, so
 * there is no read-modify-write gap for "set quantity" to race against itself.
 */
@Entity
@Table(name = "cart_items")
@IdClass(CartItemId.class)
public class CartItem {

    @Id
    @Column(name = "cart_id")
    private UUID cartId;

    @Id
    @Column(name = "sku")
    private String sku;

    @Column(nullable = false)
    private int quantity;

    protected CartItem() {
    }

    public String getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }
}

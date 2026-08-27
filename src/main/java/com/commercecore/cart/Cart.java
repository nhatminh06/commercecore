package com.commercecore.cart;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A cart records purchase intent only. It has no relationship to inventory ownership — see
 * {@code docs/cart.md}.
 */
@Entity
@Table(name = "carts")
public class Cart {

    @Id
    private UUID id;

    protected Cart() {
    }

    public Cart(UUID id) {
        this.id = id;
    }

    public UUID getId() {
        return id;
    }
}

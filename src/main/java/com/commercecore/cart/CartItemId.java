package com.commercecore.cart;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class CartItemId implements Serializable {

    private UUID cartId;
    private String sku;

    public CartItemId() {
    }

    public CartItemId(UUID cartId, String sku) {
        this.cartId = cartId;
        this.sku = sku;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CartItemId other)) {
            return false;
        }
        return Objects.equals(cartId, other.cartId) && Objects.equals(sku, other.sku);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cartId, sku);
    }
}

package com.commercecore.checkout;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class OrderItemId implements Serializable {

    private UUID orderId;
    private String sku;

    public OrderItemId() {
    }

    public OrderItemId(UUID orderId, String sku) {
        this.orderId = orderId;
        this.sku = sku;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OrderItemId other)) {
            return false;
        }
        return Objects.equals(orderId, other.orderId) && Objects.equals(sku, other.sku);
    }

    @Override
    public int hashCode() {
        return Objects.hash(orderId, sku);
    }
}

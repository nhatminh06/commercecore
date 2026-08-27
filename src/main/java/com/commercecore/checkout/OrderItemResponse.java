package com.commercecore.checkout;

import java.math.BigDecimal;

public record OrderItemResponse(String sku, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {

    static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(item.getSku(), item.getQuantity(), item.getUnitPrice(), item.getLineTotal());
    }
}

package com.commercecore.checkout;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record OrderResponse(UUID orderId, OrderStatus status, BigDecimal total, List<OrderItemResponse> items) {

    static OrderResponse of(Order order, List<OrderItem> items) {
        return new OrderResponse(order.getId(), order.getStatus(), order.getTotalAmount(),
            items.stream().map(OrderItemResponse::from).toList());
    }
}

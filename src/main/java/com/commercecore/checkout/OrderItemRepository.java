package com.commercecore.checkout;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItem, OrderItemId> {

    List<OrderItem> findByOrderId(UUID orderId);
}

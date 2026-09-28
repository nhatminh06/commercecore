package com.commercecore.checkout;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Orders are historical checkout records — read-only. No PUT/PATCH: nothing here can be edited
 * after checkout.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final CheckoutService checkoutService;
    private final OrderInspectionService orderInspectionService;

    public OrderController(CheckoutService checkoutService, OrderInspectionService orderInspectionService) {
        this.checkoutService = checkoutService;
        this.orderInspectionService = orderInspectionService;
    }

    @GetMapping("/{orderId}")
    public OrderResponse get(@PathVariable UUID orderId) {
        Order order = checkoutService.getOrder(orderId);
        return OrderResponse.of(order, checkoutService.getItems(orderId));
    }

    @GetMapping("/{orderId}/inspection")
    public OrderInspectionResponse inspect(@PathVariable UUID orderId) {
        return orderInspectionService.inspect(orderId);
    }
}

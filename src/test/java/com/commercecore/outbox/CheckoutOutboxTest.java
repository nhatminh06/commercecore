package com.commercecore.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercecore.AbstractIntegrationTest;
import com.commercecore.cart.CartService;
import com.commercecore.catalog.ProductService;
import com.commercecore.checkout.CheckoutService;
import com.commercecore.shared.BusinessRuleViolation;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves ORDER_CREATED is written atomically with the order it describes: present on success,
 * absent entirely on rollback, and never duplicated on an idempotent checkout replay.
 */
class CheckoutOutboxTest extends AbstractIntegrationTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private CartService cartService;

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private String newProductWithStockAndPrice(int stock, String price) {
        String sku = "SKU-" + UUID.randomUUID();
        productService.createProduct(sku, "Widget", new BigDecimal(price), stock);
        return sku;
    }

    private List<OutboxEvent> orderCreatedEventsFor(UUID orderId) {
        return outboxEventRepository.findAll().stream()
            .filter(e -> e.getEventType() == OutboxEventType.ORDER_CREATED && e.getAggregateId().equals(orderId))
            .toList();
    }

    @Test
    void successfulCheckoutWritesExactlyOneOrderCreatedEvent() {
        String sku = newProductWithStockAndPrice(5, "10.00");
        UUID cartId = cartService.createCart().getId();
        cartService.setItemQuantity(cartId, sku, 2);

        UUID orderId = checkoutService.checkoutIdempotently(cartId, "key-" + UUID.randomUUID()).order().getId();

        List<OutboxEvent> events = orderCreatedEventsFor(orderId);
        assertThat(events).hasSize(1);
        OutboxEvent event = events.get(0);
        assertThat(event.getAggregateType()).isEqualTo(AggregateType.ORDER);
        assertThat(event.getAggregateId()).isEqualTo(orderId);
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getPayload()).contains(orderId.toString()).contains("PENDING").contains("20.00");
    }

    @Test
    void rolledBackCheckoutWritesNoOrderCreatedEvent() {
        String skuA = newProductWithStockAndPrice(5, "10.00");
        String skuB = newProductWithStockAndPrice(0, "3.50");
        UUID cartId = cartService.createCart().getId();
        cartService.setItemQuantity(cartId, skuA, 2);
        cartService.setItemQuantity(cartId, skuB, 1);
        long before = outboxEventRepository.count();

        try {
            checkoutService.checkoutIdempotently(cartId, "key-" + UUID.randomUUID());
        } catch (BusinessRuleViolation expected) {
            // insufficient_stock — expected
        }

        assertThat(outboxEventRepository.count()).isEqualTo(before);
    }

    @Test
    void idempotentCheckoutReplayWritesNoAdditionalOrderCreatedEvent() {
        String sku = newProductWithStockAndPrice(5, "10.00");
        UUID cartId = cartService.createCart().getId();
        cartService.setItemQuantity(cartId, sku, 1);
        String key = "key-" + UUID.randomUUID();

        Set<UUID> orderIds = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            orderIds.add(checkoutService.checkoutIdempotently(cartId, key).order().getId());
        }

        assertThat(orderIds).hasSize(1);
        assertThat(orderCreatedEventsFor(orderIds.iterator().next())).hasSize(1);
    }
}

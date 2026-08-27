package com.commercecore.cart;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercecore.AbstractIntegrationTest;
import com.commercecore.catalog.ProductService;
import com.commercecore.inventory.InventoryService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * The central invariant of Milestone 2: cart quantity is purchase intent, not inventory
 * ownership. Adding to a cart must never read or change {@code inventory.available_quantity}.
 */
class CartInventorySeparationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ProductService productService;

    @Autowired
    private InventoryService inventoryService;

    private String newProductWithStock(int quantity) {
        String sku = "SKU-" + UUID.randomUUID();
        productService.createProduct(sku, "Widget", new BigDecimal("9.99"), quantity);
        return sku;
    }

    private UUID newCart() {
        return restTemplate.postForEntity("/api/carts", null, CartIdResponse.class).getBody().id();
    }

    private void putItem(UUID cartId, String sku, int quantity) {
        restTemplate.exchange("/api/carts/{cartId}/items/{sku}", HttpMethod.PUT,
            new HttpEntity<>(new SetCartItemRequest(quantity)), Void.class, cartId, sku);
    }

    @Test
    void cartQuantityMayExceedAvailableInventory() {
        String sku = newProductWithStock(2);
        UUID cartId = newCart();

        ResponseEntity<Void> response = restTemplate.exchange("/api/carts/{cartId}/items/{sku}",
            HttpMethod.PUT, new HttpEntity<>(new SetCartItemRequest(10)), Void.class, cartId, sku);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

        CartResponse cart = restTemplate.getForEntity("/api/carts/{cartId}", CartResponse.class, cartId).getBody();
        assertThat(cart.items())
            .anySatisfy(item -> {
                assertThat(item.sku()).isEqualTo(sku);
                assertThat(item.quantity()).isEqualTo(10);
            });
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(2);
    }

    @Test
    void multipleCartsMayAllHoldTheLastUnitOfStock() {
        String sku = newProductWithStock(1);
        UUID cartA = newCart();
        UUID cartB = newCart();
        UUID cartC = newCart();

        putItem(cartA, sku, 1);
        putItem(cartB, sku, 1);
        putItem(cartC, sku, 1);

        for (UUID cartId : new UUID[] {cartA, cartB, cartC}) {
            CartResponse cart =
                restTemplate.getForEntity("/api/carts/{cartId}", CartResponse.class, cartId).getBody();
            assertThat(cart.items()).anySatisfy(item -> {
                assertThat(item.sku()).isEqualTo(sku);
                assertThat(item.quantity()).isEqualTo(1);
            });
        }
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(1);
    }
}

package com.commercecore.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.commercecore.AbstractIntegrationTest;
import com.commercecore.catalog.ProductService;
import com.commercecore.shared.ApiError;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class CartItemTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ProductService productService;

    private String newProduct() {
        String sku = "SKU-" + UUID.randomUUID();
        productService.createProduct(sku, "Widget", new BigDecimal("9.99"), 100);
        return sku;
    }

    private UUID newCart() {
        return restTemplate.postForEntity("/api/carts", null, CartIdResponse.class).getBody().id();
    }

    private ResponseEntity<Void> putItem(UUID cartId, String sku, int quantity) {
        return restTemplate.exchange("/api/carts/{cartId}/items/{sku}", HttpMethod.PUT,
            new HttpEntity<>(new SetCartItemRequest(quantity)), Void.class, cartId, sku);
    }

    private ResponseEntity<ApiError> putItemExpectingError(UUID cartId, String sku, int quantity) {
        return restTemplate.exchange("/api/carts/{cartId}/items/{sku}", HttpMethod.PUT,
            new HttpEntity<>(new SetCartItemRequest(quantity)), ApiError.class, cartId, sku);
    }

    private CartResponse readCart(UUID cartId) {
        return restTemplate.getForEntity("/api/carts/{cartId}", CartResponse.class, cartId).getBody();
    }

    @Test
    void addsFirstItem() {
        UUID cartId = newCart();
        String sku = newProduct();

        ResponseEntity<Void> response = putItem(cartId, sku, 3);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(readCart(cartId).items())
            .extracting(CartItemResponse::sku, CartItemResponse::quantity)
            .containsExactly(tuple(sku, 3));
    }

    @Test
    void settingSameSkuAgainReplacesQuantityRatherThanAddingALine() {
        UUID cartId = newCart();
        String sku = newProduct();

        putItem(cartId, sku, 2);
        putItem(cartId, sku, 5);

        assertThat(readCart(cartId).items())
            .extracting(CartItemResponse::sku, CartItemResponse::quantity)
            .containsExactly(tuple(sku, 5));
    }

    @Test
    void removesItem() {
        UUID cartId = newCart();
        String sku = newProduct();
        putItem(cartId, sku, 2);

        ResponseEntity<Void> response = restTemplate.exchange("/api/carts/{cartId}/items/{sku}",
            HttpMethod.DELETE, null, Void.class, cartId, sku);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(readCart(cartId).items()).isEmpty();
    }

    @Test
    void removingMissingItemIsIdempotentSuccess() {
        UUID cartId = newCart();
        String sku = newProduct();

        ResponseEntity<Void> response = restTemplate.exchange("/api/carts/{cartId}/items/{sku}",
            HttpMethod.DELETE, null, Void.class, cartId, sku);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void unknownSkuIsRejected() {
        UUID cartId = newCart();

        ResponseEntity<ApiError> response = putItemExpectingError(cartId, "does-not-exist", 1);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("unknown_sku");
        assertThat(readCart(cartId).items()).isEmpty();
    }

    @Test
    void zeroQuantityIsRejected() {
        UUID cartId = newCart();
        String sku = newProduct();

        ResponseEntity<ApiError> response = putItemExpectingError(cartId, sku, 0);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("invalid_quantity");
        assertThat(readCart(cartId).items()).isEmpty();
    }

    @Test
    void negativeQuantityIsRejected() {
        UUID cartId = newCart();
        String sku = newProduct();

        ResponseEntity<ApiError> response = putItemExpectingError(cartId, sku, -1);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("invalid_quantity");
    }

    @Test
    void settingItemOnUnknownCartIsRejected() {
        String sku = newProduct();

        ResponseEntity<ApiError> response = putItemExpectingError(UUID.randomUUID(), sku, 1);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("cart_not_found");
    }

    @Test
    void cartsAreIsolatedFromEachOther() {
        String sku = newProduct();
        UUID cartA = newCart();
        UUID cartB = newCart();

        putItem(cartA, sku, 2);
        putItem(cartB, sku, 7);
        putItem(cartA, sku, 4);

        assertThat(readCart(cartA).items())
            .extracting(CartItemResponse::sku, CartItemResponse::quantity)
            .containsExactly(tuple(sku, 4));
        assertThat(readCart(cartB).items())
            .extracting(CartItemResponse::sku, CartItemResponse::quantity)
            .containsExactly(tuple(sku, 7));
    }

    @Test
    void cartPersistsAcrossSeparateRequests() {
        UUID cartId = newCart();
        String sku = newProduct();

        putItem(cartId, sku, 6);

        // A fresh HTTP request opens a fresh transaction/persistence context, so this read can
        // only see the item if it was actually committed to PostgreSQL, not retained in memory.
        CartResponse reloaded = readCart(cartId);

        assertThat(reloaded.items())
            .extracting(CartItemResponse::sku, CartItemResponse::quantity)
            .containsExactly(tuple(sku, 6));
    }
}

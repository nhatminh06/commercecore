package com.commercecore.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercecore.AbstractIntegrationTest;
import com.commercecore.cart.CartIdResponse;
import com.commercecore.cart.SetCartItemRequest;
import com.commercecore.catalog.ProductRepository;
import com.commercecore.catalog.ProductService;
import com.commercecore.inventory.InventoryService;
import com.commercecore.shared.ApiError;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sequential/API-level idempotency behavior: same-key replay, cross-cart key reuse, key
 * validation, and the "retry ignores later price/cart changes" semantics. Concurrency-specific
 * evidence (duplicate requests racing, cross-cart racing) is in
 * {@link CheckoutIdempotencyConcurrencyTest}.
 */
class CheckoutIdempotencyTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private CheckoutIdempotencyRepository idempotencyRepository;

    private void updatePriceAndCommit(String sku, BigDecimal price) {
        new TransactionTemplate(transactionManager).executeWithoutResult(
            status -> productRepository.updatePrice(sku, price));
    }

    private String newProductWithStockAndPrice(int quantity, String price) {
        String sku = "SKU-" + UUID.randomUUID();
        productService.createProduct(sku, "Widget", new BigDecimal(price), quantity);
        return sku;
    }

    private UUID newCart() {
        return restTemplate.postForEntity("/api/carts", null, CartIdResponse.class).getBody().id();
    }

    private void putItem(UUID cartId, String sku, int quantity) {
        restTemplate.exchange("/api/carts/{cartId}/items/{sku}", HttpMethod.PUT,
            new HttpEntity<>(new SetCartItemRequest(quantity)), Void.class, cartId, sku);
    }

    private String newIdempotencyKey() {
        return "key-" + UUID.randomUUID();
    }

    private HttpHeaders idempotencyHeaders(String key) {
        HttpHeaders headers = new HttpHeaders();
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return headers;
    }

    private ResponseEntity<OrderResponse> checkoutWithKey(UUID cartId, String key) {
        return restTemplate.exchange("/api/carts/{cartId}/checkout", HttpMethod.POST,
            new HttpEntity<>(null, idempotencyHeaders(key)), OrderResponse.class, cartId);
    }

    private ResponseEntity<ApiError> checkoutExpectingError(UUID cartId, String key) {
        return restTemplate.exchange("/api/carts/{cartId}/checkout", HttpMethod.POST,
            new HttpEntity<>(null, idempotencyHeaders(key)), ApiError.class, cartId);
    }

    @Test
    void sameKeySameCartReplaysTheOriginalOrderWithoutDecrementingAgain() {
        String sku = newProductWithStockAndPrice(10, "5.00");
        UUID cartId = newCart();
        putItem(cartId, sku, 2);
        String key = newIdempotencyKey();

        ResponseEntity<OrderResponse> first = checkoutWithKey(cartId, key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID orderId = first.getBody().orderId();
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(8);

        ResponseEntity<OrderResponse> retry = checkoutWithKey(cartId, key);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody().orderId()).isEqualTo(orderId);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(8);
    }

    @Test
    void manySequentialRetriesCreateExactlyOneOrder() {
        String sku = newProductWithStockAndPrice(10, "5.00");
        UUID cartId = newCart();
        putItem(cartId, sku, 2);
        String key = newIdempotencyKey();

        Set<UUID> orderIds = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            ResponseEntity<OrderResponse> response = checkoutWithKey(cartId, key);
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            orderIds.add(response.getBody().orderId());
        }

        assertThat(orderIds).hasSize(1);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(8);
    }

    @Test
    void sameKeyDifferentCartIsRejected() {
        String skuA = newProductWithStockAndPrice(5, "5.00");
        String skuB = newProductWithStockAndPrice(5, "5.00");
        UUID cartA = newCart();
        putItem(cartA, skuA, 1);
        UUID cartB = newCart();
        putItem(cartB, skuB, 1);
        String key = newIdempotencyKey();

        checkoutWithKey(cartA, key);

        ResponseEntity<ApiError> response = checkoutExpectingError(cartB, key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("idempotency_key_reused");
        assertThat(inventoryService.getBySku(skuB).getAvailableQuantity()).isEqualTo(5);
    }

    @Test
    void failedCheckoutDoesNotPermanentlyClaimTheKey() {
        String skuA = newProductWithStockAndPrice(5, "10.00");
        String skuB = newProductWithStockAndPrice(0, "3.50");
        UUID cartId = newCart();
        putItem(cartId, skuA, 2);
        putItem(cartId, skuB, 1);
        String key = newIdempotencyKey();

        ResponseEntity<ApiError> failed = checkoutExpectingError(cartId, key);
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(failed.getBody().code()).isEqualTo("insufficient_stock");
        assertThat(inventoryService.getBySku(skuA).getAvailableQuantity()).isEqualTo(5);
        assertThat(idempotencyRepository.findById(key)).isEmpty();

        // Same key reused against a *different* cart must not be rejected as reuse: the failed
        // attempt never committed a mapping, so the key is still completely unclaimed.
        UUID otherCart = newCart();
        putItem(otherCart, skuA, 1);
        ResponseEntity<OrderResponse> retryDifferentCart = checkoutWithKey(otherCart, key);
        assertThat(retryDifferentCart.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void retryDoesNotReflectALaterProductPriceChange() {
        String sku = newProductWithStockAndPrice(5, "10.00");
        UUID cartId = newCart();
        putItem(cartId, sku, 1);
        String key = newIdempotencyKey();

        ResponseEntity<OrderResponse> first = checkoutWithKey(cartId, key);
        UUID orderId = first.getBody().orderId();
        assertThat(first.getBody().items().get(0).unitPrice()).isEqualByComparingTo("10.00");

        updatePriceAndCommit(sku, new BigDecimal("15.00"));

        ResponseEntity<OrderResponse> retry = checkoutWithKey(cartId, key);
        assertThat(retry.getBody().orderId()).isEqualTo(orderId);
        assertThat(retry.getBody().items().get(0).unitPrice()).isEqualByComparingTo("10.00");
        assertThat(retry.getBody().total()).isEqualByComparingTo("10.00");
    }

    @Test
    void retryIgnoresCartMutationMadeAfterTheOriginalCheckout() {
        String sku = newProductWithStockAndPrice(10, "5.00");
        UUID cartId = newCart();
        putItem(cartId, sku, 1);
        String key = newIdempotencyKey();

        ResponseEntity<OrderResponse> first = checkoutWithKey(cartId, key);
        UUID orderId = first.getBody().orderId();
        assertThat(first.getBody().items().get(0).quantity()).isEqualTo(1);

        putItem(cartId, sku, 5);

        ResponseEntity<OrderResponse> retry = checkoutWithKey(cartId, key);
        assertThat(retry.getBody().orderId()).isEqualTo(orderId);
        assertThat(retry.getBody().items().get(0).quantity()).isEqualTo(1);
        // Only the original quantity (1) was ever reserved — the mutated quantity (5) was never
        // acted on.
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(9);
    }

    @Test
    void missingIdempotencyKeyIsRejected() {
        UUID cartId = newCart();

        ResponseEntity<ApiError> response = checkoutExpectingError(cartId, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("missing_idempotency_key");
    }

    @Test
    void blankIdempotencyKeyIsRejected() {
        UUID cartId = newCart();

        ResponseEntity<ApiError> response = checkoutExpectingError(cartId, "   ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("missing_idempotency_key");
    }

    @Test
    void oversizedIdempotencyKeyIsRejected() {
        String sku = newProductWithStockAndPrice(5, "5.00");
        UUID cartId = newCart();
        putItem(cartId, sku, 1);

        ResponseEntity<ApiError> response = checkoutExpectingError(cartId, "k".repeat(256));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("invalid_idempotency_key");
    }

    @Test
    void maximumLengthKeyIsAccepted() {
        String sku = newProductWithStockAndPrice(5, "5.00");
        UUID cartId = newCart();
        putItem(cartId, sku, 1);

        ResponseEntity<OrderResponse> response = checkoutWithKey(cartId, "k".repeat(255));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void idempotencyMappingIsActuallyPersistedInPostgresNotJvmMemory() {
        String sku = newProductWithStockAndPrice(5, "5.00");
        UUID cartId = newCart();
        putItem(cartId, sku, 1);
        String key = newIdempotencyKey();

        ResponseEntity<OrderResponse> response = checkoutWithKey(cartId, key);
        UUID orderId = response.getBody().orderId();

        // Read the mapping back through its own repository/table rather than trusting the HTTP
        // response — this is the actual database row the retry mechanism depends on.
        CheckoutIdempotency mapping = idempotencyRepository.findById(key).orElseThrow();
        assertThat(mapping.getCartId()).isEqualTo(cartId);
        assertThat(mapping.getOrderId()).isEqualTo(orderId);
    }
}

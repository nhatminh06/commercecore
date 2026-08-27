package com.commercecore.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.commercecore.AbstractIntegrationTest;
import com.commercecore.cart.CartIdResponse;
import com.commercecore.cart.CartItemResponse;
import com.commercecore.cart.CartResponse;
import com.commercecore.cart.SetCartItemRequest;
import com.commercecore.catalog.ProductRepository;
import com.commercecore.catalog.ProductService;
import com.commercecore.inventory.InventoryService;
import com.commercecore.reservation.InventoryReservation;
import com.commercecore.reservation.ReservationService;
import com.commercecore.reservation.ReservationStatus;
import com.commercecore.shared.ApiError;
import java.math.BigDecimal;
import java.util.List;
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

class CheckoutApiTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // ProductRepository.updatePrice is a @Modifying query and needs an active transaction to
    // run at all. The test method itself can't be @Transactional here: checkout runs over real
    // HTTP on a separate thread/connection, which would not see an update still sitting in an
    // uncommitted outer test transaction. This commits the price change immediately instead.
    private void updatePriceAndCommit(String sku, BigDecimal price) {
        new TransactionTemplate(transactionManager).executeWithoutResult(
            status -> productRepository.updatePrice(sku, price));
    }

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private ReservationService reservationService;

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
        headers.set("Idempotency-Key", key);
        return headers;
    }

    private ResponseEntity<OrderResponse> checkoutWithKey(UUID cartId, String key) {
        return restTemplate.exchange("/api/carts/{cartId}/checkout", HttpMethod.POST,
            new HttpEntity<>(null, idempotencyHeaders(key)), OrderResponse.class, cartId);
    }

    private ResponseEntity<OrderResponse> checkout(UUID cartId) {
        return checkoutWithKey(cartId, newIdempotencyKey());
    }

    private ResponseEntity<ApiError> checkoutExpectingError(UUID cartId, String key) {
        return restTemplate.exchange("/api/carts/{cartId}/checkout", HttpMethod.POST,
            new HttpEntity<>(null, idempotencyHeaders(key)), ApiError.class, cartId);
    }

    @Test
    void successfulSingleItemCheckoutCreatesActiveReservationAndPendingOrder() {
        String sku = newProductWithStockAndPrice(5, "10.00");
        UUID cartId = newCart();
        putItem(cartId, sku, 2);

        ResponseEntity<OrderResponse> response = checkout(cartId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        OrderResponse order = response.getBody();
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.total()).isEqualByComparingTo("20.00");
        assertThat(order.items())
            .extracting(OrderItemResponse::sku, OrderItemResponse::quantity, OrderItemResponse::unitPrice,
                OrderItemResponse::lineTotal)
            .containsExactly(tuple(sku, 2, new BigDecimal("10.00"), new BigDecimal("20.00")));

        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(3);

        List<InventoryReservation> reservations = reservationService.getReservationsForOrder(order.orderId());
        assertThat(reservations).hasSize(1);
        assertThat(reservations.get(0).getStatus()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(reservations.get(0).getSku()).isEqualTo(sku);
        assertThat(reservations.get(0).getQuantity()).isEqualTo(2);
    }

    @Test
    void successfulMultiItemCheckoutComputesCorrectTotals() {
        String skuA = newProductWithStockAndPrice(5, "10.00");
        String skuB = newProductWithStockAndPrice(4, "3.50");
        UUID cartId = newCart();
        putItem(cartId, skuA, 2);
        putItem(cartId, skuB, 3);

        ResponseEntity<OrderResponse> response = checkout(cartId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        OrderResponse order = response.getBody();
        assertThat(order.total()).isEqualByComparingTo("30.50");
        assertThat(order.items())
            .extracting(OrderItemResponse::sku, OrderItemResponse::lineTotal)
            .containsExactlyInAnyOrder(
                tuple(skuA, new BigDecimal("20.00")),
                tuple(skuB, new BigDecimal("10.50")));

        assertThat(inventoryService.getBySku(skuA).getAvailableQuantity()).isEqualTo(3);
        assertThat(inventoryService.getBySku(skuB).getAvailableQuantity()).isEqualTo(1);
    }

    @Test
    void emptyCartCheckoutIsRejected() {
        UUID cartId = newCart();

        ResponseEntity<ApiError> response = checkoutExpectingError(cartId, newIdempotencyKey());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("empty_cart");
    }

    @Test
    void unknownCartCheckoutIsRejected() {
        ResponseEntity<ApiError> response = checkoutExpectingError(UUID.randomUUID(), newIdempotencyKey());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("cart_not_found");
    }

    @Test
    void insufficientStockOnAnyLineRollsBackTheWholeCheckout() {
        String skuA = newProductWithStockAndPrice(5, "10.00");
        String skuB = newProductWithStockAndPrice(0, "3.50");
        UUID cartId = newCart();
        putItem(cartId, skuA, 2);
        putItem(cartId, skuB, 1);

        ResponseEntity<ApiError> response = checkoutExpectingError(cartId, newIdempotencyKey());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("insufficient_stock");

        // Neither line's stock effect nor reservation survives: A's successful reserve inside
        // the same transaction as B's failed one must roll back too.
        assertThat(inventoryService.getBySku(skuA).getAvailableQuantity()).isEqualTo(5);
        assertThat(inventoryService.getBySku(skuB).getAvailableQuantity()).isEqualTo(0);
    }

    @Test
    void checkoutSnapshotsPriceAndLaterCatalogChangesDoNotAffectTheOrder() {
        String sku = newProductWithStockAndPrice(5, "10.00");
        UUID cartId = newCart();
        putItem(cartId, sku, 1);

        updatePriceAndCommit(sku, new BigDecimal("12.00"));

        ResponseEntity<OrderResponse> checkoutResponse = checkout(cartId);
        UUID orderId = checkoutResponse.getBody().orderId();
        assertThat(checkoutResponse.getBody().items().get(0).unitPrice()).isEqualByComparingTo("12.00");

        updatePriceAndCommit(sku, new BigDecimal("15.00"));

        ResponseEntity<OrderResponse> readBack =
            restTemplate.getForEntity("/api/orders/{orderId}", OrderResponse.class, orderId);

        assertThat(readBack.getBody().items().get(0).unitPrice()).isEqualByComparingTo("12.00");
        assertThat(readBack.getBody().total()).isEqualByComparingTo("12.00");
    }

    @Test
    void successfulCheckoutLeavesCartContentsIntact() {
        String sku = newProductWithStockAndPrice(5, "10.00");
        UUID cartId = newCart();
        putItem(cartId, sku, 2);

        checkout(cartId);

        ResponseEntity<CartResponse> cartResponse =
            restTemplate.getForEntity("/api/carts/{cartId}", CartResponse.class, cartId);
        assertThat(cartResponse.getBody().items())
            .extracting(CartItemResponse::sku, CartItemResponse::quantity)
            .containsExactly(tuple(sku, 2));
    }

    @Test
    void unknownOrderReturnsNotFound() {
        ResponseEntity<ApiError> response =
            restTemplate.getForEntity("/api/orders/{orderId}", ApiError.class, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("order_not_found");
    }
}

package com.commercecore.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercecore.AbstractIntegrationTest;
import com.commercecore.cart.CartIdResponse;
import com.commercecore.cart.SetCartItemRequest;
import com.commercecore.catalog.ProductService;
import com.commercecore.checkout.OrderResponse;
import com.commercecore.checkout.OrderStatus;
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

/**
 * Success/decline/timeout flows exercised through the real HTTP API, plus amount/order
 * validation. Concurrency-specific evidence (duplicate initiation racing) is in
 * {@link PaymentConcurrencyTest}.
 */
class PaymentApiTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ProductService productService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private FakePaymentProvider fakePaymentProvider;

    @Autowired
    private ReservationService reservationService;

    private String newProductWithStockAndPrice(int quantity, String price) {
        String sku = "SKU-" + UUID.randomUUID();
        productService.createProduct(sku, "Widget", new BigDecimal(price), quantity);
        return sku;
    }

    private UUID checkoutOneItem(String sku, int quantity) {
        UUID cartId = restTemplate.postForEntity("/api/carts", null, CartIdResponse.class).getBody().id();
        restTemplate.exchange("/api/carts/{cartId}/items/{sku}", HttpMethod.PUT,
            new HttpEntity<>(new SetCartItemRequest(quantity)), Void.class, cartId, sku);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", "key-" + UUID.randomUUID());
        ResponseEntity<OrderResponse> checkout = restTemplate.exchange("/api/carts/{cartId}/checkout",
            HttpMethod.POST, new HttpEntity<>(null, headers), OrderResponse.class, cartId);
        return checkout.getBody().orderId();
    }

    private ResponseEntity<PaymentResponse> initiate(UUID orderId) {
        return restTemplate.postForEntity("/api/orders/{orderId}/payment", null, PaymentResponse.class, orderId);
    }

    @Test
    void successfulAuthorizationPersistsAmountStatusAndProviderReference() {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.SUCCESS);
        String sku = newProductWithStockAndPrice(5, "10.00");
        UUID orderId = checkoutOneItem(sku, 2);

        ResponseEntity<PaymentResponse> response = initiate(orderId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PaymentResponse payment = response.getBody();
        assertThat(payment.orderId()).isEqualTo(orderId);
        assertThat(payment.amount()).isEqualByComparingTo("20.00");
        assertThat(payment.status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(payment.providerReference()).isNotBlank();

        // Milestone 6 deliberately isolates payment truth from order/reservation truth: an
        // AUTHORIZED payment does not confirm the order or touch its reservations.
        ResponseEntity<OrderResponse> order =
            restTemplate.getForEntity("/api/orders/{orderId}", OrderResponse.class, orderId);
        assertThat(order.getBody().status()).isEqualTo(OrderStatus.PENDING);
        List<InventoryReservation> reservations = reservationService.getReservationsForOrder(orderId);
        assertThat(reservations).hasSize(1);
        assertThat(reservations.get(0).getStatus()).isEqualTo(ReservationStatus.ACTIVE);
    }

    @Test
    void declinedAuthorizationLeavesOrderPendingAndReservationActive() {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.DECLINED);
        String sku = newProductWithStockAndPrice(5, "10.00");
        UUID orderId = checkoutOneItem(sku, 2);

        ResponseEntity<PaymentResponse> response = initiate(orderId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(PaymentStatus.FAILED);

        ResponseEntity<OrderResponse> order =
            restTemplate.getForEntity("/api/orders/{orderId}", OrderResponse.class, orderId);
        assertThat(order.getBody().status()).isEqualTo(OrderStatus.PENDING);
        // Stock stays held: inventory was never restored, and the reservation stays ACTIVE,
        // because reservation/order cleanup on payment failure is deliberately out of scope for
        // this milestone.
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(3);
        List<InventoryReservation> reservations = reservationService.getReservationsForOrder(orderId);
        assertThat(reservations).hasSize(1);
        assertThat(reservations.get(0).getStatus()).isEqualTo(ReservationStatus.ACTIVE);
    }

    @Test
    void timeoutAfterProviderSuccessProducesUnknownNotFailed() {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.TIMEOUT_AFTER_PROCESSING);
        String sku = newProductWithStockAndPrice(5, "10.00");
        UUID orderId = checkoutOneItem(sku, 1);

        ResponseEntity<PaymentResponse> response = initiate(orderId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(response.getBody().providerReference()).isNull();

        // The strongest evidence in this milestone: the provider really did process this
        // request — CommerceCore's UNKNOWN reflects its own uncertainty, not an actual failure.
        assertThat(fakePaymentProvider.hasProviderSideAuthorization(response.getBody().paymentId())).isTrue();

        ResponseEntity<OrderResponse> order =
            restTemplate.getForEntity("/api/orders/{orderId}", OrderResponse.class, orderId);
        assertThat(order.getBody().status()).isEqualTo(OrderStatus.PENDING);
        List<InventoryReservation> reservations = reservationService.getReservationsForOrder(orderId);
        assertThat(reservations).hasSize(1);
        assertThat(reservations.get(0).getStatus()).isEqualTo(ReservationStatus.ACTIVE);
    }

    @Test
    void paymentAmountAlwaysComesFromOrderTotalNeverFromClient() {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.SUCCESS);
        String sku = newProductWithStockAndPrice(5, "10.00");
        UUID orderId = checkoutOneItem(sku, 3);

        // The endpoint accepts no body at all — there is no field for a client to smuggle an
        // amount through. Confirm the persisted amount matches the order total regardless.
        ResponseEntity<PaymentResponse> response = initiate(orderId);

        assertThat(response.getBody().amount()).isEqualByComparingTo("30.00");
    }

    @Test
    void unknownOrderIsRejectedWithoutCallingProvider() {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.SUCCESS);
        int before = fakePaymentProvider.callCount();

        ResponseEntity<ApiError> response =
            restTemplate.postForEntity("/api/orders/{orderId}/payment", null, ApiError.class, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("order_not_found");
        assertThat(fakePaymentProvider.callCount()).isEqualTo(before);
    }

    @Test
    void getPaymentForOrderWithNoPaymentYetReturnsNotFound() {
        String sku = newProductWithStockAndPrice(5, "10.00");
        UUID orderId = checkoutOneItem(sku, 1);

        ResponseEntity<ApiError> response =
            restTemplate.getForEntity("/api/orders/{orderId}/payment", ApiError.class, orderId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("payment_not_found");
    }

    @Test
    void getPaymentReflectsTheAuthorizedPayment() {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.SUCCESS);
        String sku = newProductWithStockAndPrice(5, "10.00");
        UUID orderId = checkoutOneItem(sku, 1);
        initiate(orderId);

        ResponseEntity<PaymentResponse> response =
            restTemplate.getForEntity("/api/orders/{orderId}/payment", PaymentResponse.class, orderId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(PaymentStatus.AUTHORIZED);
    }

    @Test
    void onePaymentRowExistsPerOrder() {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.SUCCESS);
        String sku = newProductWithStockAndPrice(5, "10.00");
        UUID orderId = checkoutOneItem(sku, 1);

        UUID firstPaymentId = initiate(orderId).getBody().paymentId();
        UUID secondPaymentId = initiate(orderId).getBody().paymentId();

        assertThat(secondPaymentId).isEqualTo(firstPaymentId);
    }
}

package com.commercecore.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercecore.AbstractIntegrationTest;
import com.commercecore.cart.CartService;
import com.commercecore.catalog.ProductService;
import com.commercecore.checkout.CheckoutService;
import com.commercecore.checkout.Order;
import com.commercecore.checkout.OrderRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Core webhook flows — first delivery, duplicate delivery, event-ID reuse, terminal conflicts,
 * malformed events, and the milestone's centerpiece: UNKNOWN resolution — exercised over the
 * real HTTP API. Concurrency-specific evidence is in {@link PaymentWebhookConcurrencyTest}.
 */
class PaymentWebhookApiTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ProductService productService;

    @Autowired
    private CartService cartService;

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private FakePaymentProvider fakePaymentProvider;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private PaymentWebhookEventRepository webhookEventRepository;

    private UUID checkoutOneItem(int stock, String price, int quantity) {
        String sku = "SKU-" + UUID.randomUUID();
        productService.createProduct(sku, "Widget", new BigDecimal(price), stock);
        UUID cartId = cartService.createCart().getId();
        cartService.setItemQuantity(cartId, sku, quantity);
        return checkoutService.checkoutIdempotently(cartId, "key-" + UUID.randomUUID()).order().getId();
    }

    private ResponseEntity<PaymentResponse> postWebhook(PaymentWebhookRequest request) {
        return restTemplate.postForEntity("/api/webhooks/payments", request, PaymentResponse.class);
    }

    private ResponseEntity<ApiError> postWebhookExpectingError(PaymentWebhookRequest request) {
        return restTemplate.postForEntity("/api/webhooks/payments", request, ApiError.class);
    }

    @Test
    void firstAuthorizedWebhookTransitionsPendingPayment() {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.TIMEOUT_AFTER_PROCESSING);
        UUID orderId = checkoutOneItem(5, "10.00", 1);
        Payment payment = paymentService.initiatePayment(orderId);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);

        String reference = "pay_" + UUID.randomUUID();
        ResponseEntity<PaymentResponse> response = postWebhook(
            new PaymentWebhookRequest("evt-" + UUID.randomUUID(), "PAYMENT_AUTHORIZED", payment.getId().toString(),
                reference));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(response.getBody().providerReference()).isEqualTo(reference);
    }

    @Test
    void unknownResolutionByAuthenticProviderWebhook() {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.TIMEOUT_AFTER_PROCESSING);
        UUID orderId = checkoutOneItem(5, "10.00", 1);
        Payment payment = paymentService.initiatePayment(orderId);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(fakePaymentProvider.hasProviderSideAuthorization(payment.getId())).isTrue();
        String providerSideReference = fakePaymentProvider.getProviderSideReference(payment.getId());
        assertThat(providerSideReference).isNotBlank();

        ResponseEntity<PaymentResponse> response = postWebhook(
            new PaymentWebhookRequest("evt-" + UUID.randomUUID(), "PAYMENT_AUTHORIZED", payment.getId().toString(),
                providerSideReference));

        assertThat(response.getBody().status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(response.getBody().providerReference()).isEqualTo(providerSideReference);

        // Sending the identical webhook twenty more times must not change anything further.
        for (int i = 0; i < 20; i++) {
            ResponseEntity<PaymentResponse> retry = postWebhook(
                new PaymentWebhookRequest("evt-" + UUID.randomUUID(), "PAYMENT_AUTHORIZED", payment.getId().toString(),
                    providerSideReference));
            assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(retry.getBody().status()).isEqualTo(PaymentStatus.AUTHORIZED);
        }
    }

    @Test
    void pendingToFailedViaDeclinedWebhook() {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.DECLINED);
        UUID orderId = checkoutOneItem(5, "10.00", 1);
        Payment payment = paymentService.initiatePayment(orderId);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);

        // FAILED + DECLINED is an idempotent no-op.
        ResponseEntity<PaymentResponse> response = postWebhook(
            new PaymentWebhookRequest("evt-" + UUID.randomUUID(), "PAYMENT_DECLINED", payment.getId().toString(), null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void duplicateIdenticalDeliveryIsSafeAndCreatesOneReceipt() {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.TIMEOUT_AFTER_PROCESSING);
        UUID orderId = checkoutOneItem(5, "10.00", 1);
        Payment payment = paymentService.initiatePayment(orderId);
        String eventId = "evt-" + UUID.randomUUID();
        String reference = "pay_" + UUID.randomUUID();
        PaymentWebhookRequest request =
            new PaymentWebhookRequest(eventId, "PAYMENT_AUTHORIZED", payment.getId().toString(), reference);

        for (int i = 0; i < 20; i++) {
            ResponseEntity<PaymentResponse> response = postWebhook(request);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().status()).isEqualTo(PaymentStatus.AUTHORIZED);
        }

        assertThat(webhookEventRepository.findById(eventId)).isPresent();
    }

    @Test
    void sameEventIdWithDifferentPayloadIsRejected() {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.TIMEOUT_AFTER_PROCESSING);
        UUID orderId = checkoutOneItem(5, "10.00", 1);
        Payment payment = paymentService.initiatePayment(orderId);
        String eventId = "evt-" + UUID.randomUUID();
        String reference = "pay_" + UUID.randomUUID();

        postWebhook(new PaymentWebhookRequest(eventId, "PAYMENT_AUTHORIZED", payment.getId().toString(), reference));

        ResponseEntity<ApiError> response = postWebhookExpectingError(
            new PaymentWebhookRequest(eventId, "PAYMENT_DECLINED", payment.getId().toString(), null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("provider_event_id_reused");

        // Original transition is unaffected by the rejected reuse attempt.
        ResponseEntity<PaymentResponse> current = restTemplate.getForEntity("/api/orders/{orderId}/payment",
            PaymentResponse.class, orderId);
        assertThat(current.getBody().status()).isEqualTo(PaymentStatus.AUTHORIZED);
    }

    @Test
    void sameEventIdForDifferentPaymentIsRejected() {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.TIMEOUT_AFTER_PROCESSING);
        UUID orderA = checkoutOneItem(5, "10.00", 1);
        UUID orderB = checkoutOneItem(5, "10.00", 1);
        Payment paymentA = paymentService.initiatePayment(orderA);
        Payment paymentB = paymentService.initiatePayment(orderB);
        String eventId = "evt-" + UUID.randomUUID();
        String reference = "pay_" + UUID.randomUUID();

        postWebhook(new PaymentWebhookRequest(eventId, "PAYMENT_AUTHORIZED", paymentA.getId().toString(), reference));

        ResponseEntity<ApiError> response = postWebhookExpectingError(
            new PaymentWebhookRequest(eventId, "PAYMENT_AUTHORIZED", paymentB.getId().toString(), reference));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("provider_event_id_reused");
    }

    @Test
    void authorizedThenDeclinedIsRejectedAsConflict() {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.TIMEOUT_AFTER_PROCESSING);
        UUID orderId = checkoutOneItem(5, "10.00", 1);
        Payment payment = paymentService.initiatePayment(orderId);
        String reference = "pay_" + UUID.randomUUID();
        postWebhook(new PaymentWebhookRequest("evt-" + UUID.randomUUID(), "PAYMENT_AUTHORIZED",
            payment.getId().toString(), reference));

        ResponseEntity<ApiError> response = postWebhookExpectingError(
            new PaymentWebhookRequest("evt-" + UUID.randomUUID(), "PAYMENT_DECLINED", payment.getId().toString(), null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("conflicting_payment_event");

        ResponseEntity<PaymentResponse> current = restTemplate.getForEntity("/api/orders/{orderId}/payment",
            PaymentResponse.class, orderId);
        assertThat(current.getBody().status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(current.getBody().providerReference()).isEqualTo(reference);
    }

    @Test
    void declinedThenAuthorizedIsRejectedAsConflict() {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.DECLINED);
        UUID orderId = checkoutOneItem(5, "10.00", 1);
        Payment payment = paymentService.initiatePayment(orderId);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);

        ResponseEntity<ApiError> response = postWebhookExpectingError(
            new PaymentWebhookRequest("evt-" + UUID.randomUUID(), "PAYMENT_AUTHORIZED", payment.getId().toString(),
                "pay_" + UUID.randomUUID()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("conflicting_payment_event");

        ResponseEntity<PaymentResponse> current = restTemplate.getForEntity("/api/orders/{orderId}/payment",
            PaymentResponse.class, orderId);
        assertThat(current.getBody().status()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void authorizedWithMismatchedReferenceIsRejectedAsConflict() {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.TIMEOUT_AFTER_PROCESSING);
        UUID orderId = checkoutOneItem(5, "10.00", 1);
        Payment payment = paymentService.initiatePayment(orderId);
        String originalReference = "pay_" + UUID.randomUUID();
        postWebhook(new PaymentWebhookRequest("evt-" + UUID.randomUUID(), "PAYMENT_AUTHORIZED",
            payment.getId().toString(), originalReference));

        ResponseEntity<ApiError> response = postWebhookExpectingError(
            new PaymentWebhookRequest("evt-" + UUID.randomUUID(), "PAYMENT_AUTHORIZED", payment.getId().toString(),
                "pay_" + UUID.randomUUID()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("conflicting_payment_event");
    }

    @Test
    void unknownPaymentIsRejected() {
        ResponseEntity<ApiError> response = postWebhookExpectingError(
            new PaymentWebhookRequest("evt-" + UUID.randomUUID(), "PAYMENT_AUTHORIZED", UUID.randomUUID().toString(),
                "pay_" + UUID.randomUUID()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("payment_not_found");
    }

    @Test
    void malformedEventsAreRejectedWithoutAnyReceiptOrPaymentChange() {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.TIMEOUT_AFTER_PROCESSING);
        UUID orderId = checkoutOneItem(5, "10.00", 1);
        Payment payment = paymentService.initiatePayment(orderId);
        String validPaymentId = payment.getId().toString();

        assertThat(postWebhookExpectingError(
            new PaymentWebhookRequest(null, "PAYMENT_AUTHORIZED", validPaymentId, "pay_x")).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postWebhookExpectingError(
            new PaymentWebhookRequest("   ", "PAYMENT_AUTHORIZED", validPaymentId, "pay_x")).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postWebhookExpectingError(
            new PaymentWebhookRequest("e".repeat(256), "PAYMENT_AUTHORIZED", validPaymentId, "pay_x")).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postWebhookExpectingError(
            new PaymentWebhookRequest("evt-1", "PAYMENT_AUTHORIZED", null, "pay_x")).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postWebhookExpectingError(
            new PaymentWebhookRequest("evt-2", "PAYMENT_AUTHORIZED", "not-a-uuid", "pay_x")).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postWebhookExpectingError(
            new PaymentWebhookRequest("evt-3", "SOMETHING_ELSE", validPaymentId, "pay_x")).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postWebhookExpectingError(
            new PaymentWebhookRequest("evt-4", "PAYMENT_AUTHORIZED", validPaymentId, null)).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);

        // Still UNKNOWN — none of the malformed attempts changed anything.
        ResponseEntity<PaymentResponse> current = restTemplate.getForEntity("/api/orders/{orderId}/payment",
            PaymentResponse.class, orderId);
        assertThat(current.getBody().status()).isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    void webhookProcessingNeverCallsTheProviderAgain() {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.TIMEOUT_AFTER_PROCESSING);
        UUID orderId = checkoutOneItem(5, "10.00", 1);
        Payment payment = paymentService.initiatePayment(orderId);
        int callsBefore = fakePaymentProvider.callCount();

        postWebhook(new PaymentWebhookRequest("evt-" + UUID.randomUUID(), "PAYMENT_AUTHORIZED",
            payment.getId().toString(), "pay_" + UUID.randomUUID()));

        assertThat(fakePaymentProvider.callCount()).isEqualTo(callsBefore);
    }

    @Test
    void orderAndReservationsAreUntouchedByWebhookResolution() {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.TIMEOUT_AFTER_PROCESSING);
        UUID orderId = checkoutOneItem(5, "10.00", 1);
        Payment payment = paymentService.initiatePayment(orderId);

        postWebhook(new PaymentWebhookRequest("evt-" + UUID.randomUUID(), "PAYMENT_AUTHORIZED",
            payment.getId().toString(), "pay_" + UUID.randomUUID()));

        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus().toString()).isEqualTo("PENDING");
        List<InventoryReservation> reservations = reservationService.getReservationsForOrder(orderId);
        assertThat(reservations).hasSize(1);
        assertThat(reservations.get(0).getStatus()).isEqualTo(ReservationStatus.ACTIVE);
    }
}

package com.commercecore.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercecore.AbstractIntegrationTest;
import com.commercecore.cart.CartService;
import com.commercecore.catalog.ProductService;
import com.commercecore.payment.FakePaymentProvider;
import com.commercecore.payment.Payment;
import com.commercecore.payment.PaymentRepository;
import com.commercecore.payment.PaymentService;
import com.commercecore.payment.PaymentStatus;
import com.commercecore.reservation.ReservationRepository;
import com.commercecore.reservation.ReservationStatus;
import com.commercecore.shared.ApiError;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class OrderInspectionApiTest extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ProductService productService;
    @Autowired private CartService cartService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private FakePaymentProvider fakePaymentProvider;

    private UUID createOrder() {
        String sku = "INSPECT-" + UUID.randomUUID();
        productService.createProduct(sku, "Inspection product", new BigDecimal("19.95"), 5);
        UUID cartId = cartService.createCart().getId();
        cartService.setItemQuantity(cartId, sku, 2);
        return checkoutService.checkoutIdempotently(cartId, "inspection-" + UUID.randomUUID()).order().getId();
    }

    @Test
    void returnsStoredOrderAndReservationStateWithoutMutatingEither() {
        UUID orderId = createOrder();
        long paymentCountBefore = paymentRepository.count();
        long reservationCountBefore = reservationRepository.count();

        ResponseEntity<OrderInspectionResponse> response = restTemplate.getForEntity(
            "/api/orders/{orderId}/inspection", OrderInspectionResponse.class, orderId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        OrderInspectionResponse inspection = response.getBody();
        assertThat(inspection.order().orderId()).isEqualTo(orderId);
        assertThat(inspection.order().status()).isEqualTo(OrderStatus.PENDING);
        assertThat(inspection.order().total()).isEqualByComparingTo("39.90");
        assertThat(inspection.payment()).isNull();
        assertThat(inspection.reconciliation()).isNull();
        assertThat(inspection.reservations()).singleElement().satisfies(reservation -> {
            assertThat(reservation.status()).isEqualTo(ReservationStatus.ACTIVE);
            assertThat(reservation.quantity()).isEqualTo(2);
        });
        assertThat(paymentRepository.count()).isEqualTo(paymentCountBefore);
        assertThat(reservationRepository.count()).isEqualTo(reservationCountBefore);
    }

    @Test
    void returnsUnknownPaymentAsObservedWithoutQueryingProviderTruth() {
        UUID orderId = createOrder();
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.TIMEOUT_AFTER_PROCESSING);
        Payment payment = paymentService.initiatePayment(orderId);
        int providerCallsBefore = fakePaymentProvider.callCount();
        int lookupCallsBefore = fakePaymentProvider.lookupCallCount();

        OrderInspectionResponse inspection = restTemplate.getForObject(
            "/api/orders/{orderId}/inspection", OrderInspectionResponse.class, orderId);

        assertThat(inspection.payment().paymentId()).isEqualTo(payment.getId());
        assertThat(inspection.payment().orderId()).isEqualTo(orderId);
        assertThat(inspection.payment().status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(inspection.payment().providerReference()).isNull();
        assertThat(inspection.reservations()).allMatch(r -> r.status() == ReservationStatus.ACTIVE);
        assertThat(fakePaymentProvider.callCount()).isEqualTo(providerCallsBefore);
        assertThat(fakePaymentProvider.lookupCallCount()).isEqualTo(lookupCallsBefore);
    }

    @Test
    void missingOrderUsesExistingNotFoundContract() {
        ResponseEntity<ApiError> response = restTemplate.getForEntity(
            "/api/orders/{orderId}/inspection", ApiError.class, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("order_not_found");
    }
}

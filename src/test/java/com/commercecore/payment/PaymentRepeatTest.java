package com.commercecore.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercecore.AbstractIntegrationTest;
import com.commercecore.cart.CartService;
import com.commercecore.catalog.ProductService;
import com.commercecore.checkout.CheckoutService;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Repeated payment initiation after each terminal (or ambiguous) outcome must not call the
 * provider again — proven here via {@link FakePaymentProvider#callCount()}, not just by
 * inspecting the returned payment.
 */
class PaymentRepeatTest extends AbstractIntegrationTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private CartService cartService;

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private FakePaymentProvider fakePaymentProvider;

    private UUID checkoutOneItem(int stock, String price, int quantity) {
        String sku = "SKU-" + UUID.randomUUID();
        productService.createProduct(sku, "Widget", new BigDecimal(price), stock);
        UUID cartId = cartService.createCart().getId();
        cartService.setItemQuantity(cartId, sku, quantity);
        return checkoutService.checkoutIdempotently(cartId, "key-" + UUID.randomUUID()).order().getId();
    }

    @Test
    void repeatAfterAuthorizedReturnsSamePaymentWithoutCallingProviderAgain() {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.SUCCESS);
        UUID orderId = checkoutOneItem(5, "10.00", 1);

        Payment first = paymentService.initiatePayment(orderId);
        int callsAfterFirst = fakePaymentProvider.callCount();
        assertThat(first.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);

        Payment second = paymentService.initiatePayment(orderId);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(fakePaymentProvider.callCount()).isEqualTo(callsAfterFirst);
    }

    @Test
    void repeatAfterFailedReturnsSamePaymentWithoutCallingProviderAgain() {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.DECLINED);
        UUID orderId = checkoutOneItem(5, "10.00", 1);

        Payment first = paymentService.initiatePayment(orderId);
        int callsAfterFirst = fakePaymentProvider.callCount();
        assertThat(first.getStatus()).isEqualTo(PaymentStatus.FAILED);

        Payment second = paymentService.initiatePayment(orderId);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(fakePaymentProvider.callCount()).isEqualTo(callsAfterFirst);
    }

    @Test
    void repeatAfterUnknownDoesNotBlindlyRetryTheProvider() {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.TIMEOUT_AFTER_PROCESSING);
        UUID orderId = checkoutOneItem(5, "10.00", 1);

        Payment first = paymentService.initiatePayment(orderId);
        int callsAfterFirst = fakePaymentProvider.callCount();
        assertThat(first.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);

        Payment second = paymentService.initiatePayment(orderId);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(fakePaymentProvider.callCount()).isEqualTo(callsAfterFirst);
    }

    @Test
    void twentySequentialInitiationsCreateExactlyOnePaymentAndOneProviderCall() {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.SUCCESS);
        UUID orderId = checkoutOneItem(5, "10.00", 1);
        int callsBefore = fakePaymentProvider.callCount();

        Set<UUID> paymentIds = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            paymentIds.add(paymentService.initiatePayment(orderId).getId());
        }

        assertThat(paymentIds).hasSize(1);
        assertThat(fakePaymentProvider.callCount()).isEqualTo(callsBefore + 1);
    }
}

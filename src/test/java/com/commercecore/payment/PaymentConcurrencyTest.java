package com.commercecore.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercecore.AbstractIntegrationTest;
import com.commercecore.cart.CartService;
import com.commercecore.catalog.ProductService;
import com.commercecore.checkout.CheckoutService;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The milestone's hardest guarantee: concurrent duplicate payment initiations for the same order
 * must result in exactly one provider call, not merely one payment row (a unique constraint
 * alone can't prevent the provider from being called twice before one INSERT loses — see
 * {@code docs/payments.md}).
 */
class PaymentConcurrencyTest extends AbstractIntegrationTest {

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
    void concurrentInitiationForTheSameOrderCreatesExactlyOnePaymentAndOneProviderCall() throws Exception {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.SUCCESS);
        UUID orderId = checkoutOneItem(5, "10.00", 1);
        int callsBefore = fakePaymentProvider.callCount();
        int attempts = 20;

        CyclicBarrier startingLine = new CyclicBarrier(attempts);
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch done = new CountDownLatch(attempts);
        Set<UUID> paymentIds = ConcurrentHashMap.newKeySet();

        try {
            for (int i = 0; i < attempts; i++) {
                executor.submit(() -> {
                    try {
                        startingLine.await();
                        paymentIds.add(paymentService.initiatePayment(orderId).getId());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdown();
        }

        assertThat(paymentIds).hasSize(1);
        assertThat(fakePaymentProvider.callCount()).isEqualTo(callsBefore + 1);

        Payment finalState = paymentService.getPaymentByOrder(orderId);
        assertThat(finalState.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(finalState.getProviderReference()).isNotBlank();
    }
}

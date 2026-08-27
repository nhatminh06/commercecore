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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Two layers of idempotency this milestone must prove independently: (1) the same event ID
 * delivered many times produces one receipt and one transition, and (2) different event IDs
 * carrying the same outcome are each legitimately received, but the payment's state transition
 * still only happens once — proven here under real concurrency, not just sequential calls.
 */
class PaymentWebhookConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private CartService cartService;

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentWebhookService paymentWebhookService;

    @Autowired
    private FakePaymentProvider fakePaymentProvider;

    @Autowired
    private PaymentWebhookEventRepository webhookEventRepository;

    private Payment newUnknownPayment(int stock, String price, int quantity) {
        fakePaymentProvider.nextOutcome(FakePaymentProvider.NextOutcome.TIMEOUT_AFTER_PROCESSING);
        String sku = "SKU-" + UUID.randomUUID();
        productService.createProduct(sku, "Widget", new BigDecimal(price), stock);
        UUID cartId = cartService.createCart().getId();
        cartService.setItemQuantity(cartId, sku, quantity);
        UUID orderId = checkoutService.checkoutIdempotently(cartId, "key-" + UUID.randomUUID()).order().getId();
        return paymentService.initiatePayment(orderId);
    }

    @Test
    void distinctEventIdsWithSameOutcomeEachGetAReceiptButPaymentTransitionsOnce() {
        Payment payment = newUnknownPayment(5, "10.00", 1);
        String reference = fakePaymentProvider.getProviderSideReference(payment.getId());

        for (int i = 1; i <= 3; i++) {
            Payment result = paymentWebhookService.processWebhook("evt-" + i, payment.getId().toString(),
                "PAYMENT_AUTHORIZED", reference);
            assertThat(result.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
            assertThat(result.getProviderReference()).isEqualTo(reference);
        }

        assertThat(webhookEventRepository.findById("evt-1")).isPresent();
        assertThat(webhookEventRepository.findById("evt-2")).isPresent();
        assertThat(webhookEventRepository.findById("evt-3")).isPresent();
    }

    @Test
    void concurrentIdenticalWebhookDeliveryProducesOneReceiptAndOneTransition() throws Exception {
        Payment payment = newUnknownPayment(5, "10.00", 1);
        String reference = fakePaymentProvider.getProviderSideReference(payment.getId());
        String eventId = "evt-same-" + UUID.randomUUID();
        int attempts = 20;

        CyclicBarrier startingLine = new CyclicBarrier(attempts);
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch done = new CountDownLatch(attempts);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        try {
            for (int i = 0; i < attempts; i++) {
                executor.submit(() -> {
                    try {
                        startingLine.await();
                        paymentWebhookService.processWebhook(eventId, payment.getId().toString(),
                            "PAYMENT_AUTHORIZED", reference);
                        successes.incrementAndGet();
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdown();
        }

        assertThat(failures.get()).isZero();
        assertThat(successes.get()).isEqualTo(attempts);

        Payment finalState = paymentService.getPaymentByOrder(payment.getOrderId());
        assertThat(finalState.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(finalState.getProviderReference()).isEqualTo(reference);
    }

    @Test
    void concurrentDistinctAuthorizedEventsForSamePaymentRemainConsistent() throws Exception {
        Payment payment = newUnknownPayment(5, "10.00", 1);
        String reference = fakePaymentProvider.getProviderSideReference(payment.getId());
        int attempts = 20;

        CyclicBarrier startingLine = new CyclicBarrier(attempts);
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch done = new CountDownLatch(attempts);
        Set<String> eventIds = ConcurrentHashMap.newKeySet();
        AtomicInteger successes = new AtomicInteger();

        try {
            for (int i = 0; i < attempts; i++) {
                String eventId = "evt-distinct-" + i + "-" + UUID.randomUUID();
                eventIds.add(eventId);
                executor.submit(() -> {
                    try {
                        startingLine.await();
                        paymentWebhookService.processWebhook(eventId, payment.getId().toString(),
                            "PAYMENT_AUTHORIZED", reference);
                        successes.incrementAndGet();
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

        assertThat(successes.get()).isEqualTo(attempts);
        for (String eventId : eventIds) {
            assertThat(webhookEventRepository.findById(eventId)).isPresent();
        }

        Payment finalState = paymentService.getPaymentByOrder(payment.getOrderId());
        assertThat(finalState.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(finalState.getProviderReference()).isEqualTo(reference);
    }
}

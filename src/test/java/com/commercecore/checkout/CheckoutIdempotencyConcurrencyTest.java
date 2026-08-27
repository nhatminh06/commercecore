package com.commercecore.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercecore.AbstractIntegrationTest;
import com.commercecore.cart.CartService;
import com.commercecore.catalog.ProductService;
import com.commercecore.inventory.InventoryRepository;
import com.commercecore.inventory.InventoryService;
import com.commercecore.reservation.InventoryReservation;
import com.commercecore.reservation.ReservationService;
import com.commercecore.reservation.ReservationStatus;
import com.commercecore.shared.BusinessRuleViolation;
import java.math.BigDecimal;
import java.util.List;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The hard part of this milestone: proving that concurrent requests sharing an idempotency key
 * cannot each execute checkout — only one may, and every other caller must resolve to that same
 * result (or, for a genuinely different cart under the same key, a conflict). Calls the service
 * directly (as the other *ConcurrencyTest classes in this project do) rather than through HTTP,
 * since header validation is already covered by {@link CheckoutIdempotencyTest}.
 */
class CheckoutIdempotencyConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private CartService cartService;

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private String newProductWithStockAndPrice(int quantity, String price) {
        String sku = "SKU-" + UUID.randomUUID();
        productService.createProduct(sku, "Widget", new BigDecimal(price), quantity);
        return sku;
    }

    private UUID newCartWithItem(String sku, int quantity) {
        UUID cartId = cartService.createCart().getId();
        cartService.setItemQuantity(cartId, sku, quantity);
        return cartId;
    }

    private void restoreStockAndCommit(String sku, int quantity) {
        new TransactionTemplate(transactionManager).executeWithoutResult(
            status -> inventoryRepository.restore(sku, quantity));
    }

    @Test
    void concurrentDuplicateRequestsWithSameKeyCreateExactlyOneOrder() throws Exception {
        String sku = newProductWithStockAndPrice(10, "5.00");
        UUID cartId = newCartWithItem(sku, 2);
        String key = "dup-key-" + UUID.randomUUID();
        int attempts = 20;

        CyclicBarrier startingLine = new CyclicBarrier(attempts);
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch done = new CountDownLatch(attempts);
        Set<UUID> orderIds = ConcurrentHashMap.newKeySet();
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        try {
            for (int i = 0; i < attempts; i++) {
                executor.submit(() -> {
                    try {
                        startingLine.await();
                        CheckoutResult result = checkoutService.checkoutIdempotently(cartId, key);
                        orderIds.add(result.order().getId());
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
        assertThat(orderIds).hasSize(1);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(8);

        List<InventoryReservation> reservations =
            reservationService.getReservationsForOrder(orderIds.iterator().next());
        assertThat(reservations).hasSize(1);
        assertThat(reservations.get(0).getStatus()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(reservations.get(0).getQuantity()).isEqualTo(2);
    }

    @Test
    void concurrentCrossCartKeyReuseHasExactlyOneWinnerAndOneConflict() throws Exception {
        String skuA = newProductWithStockAndPrice(5, "5.00");
        String skuB = newProductWithStockAndPrice(5, "5.00");
        UUID cartA = newCartWithItem(skuA, 1);
        UUID cartB = newCartWithItem(skuB, 1);
        String key = "conflict-key-" + UUID.randomUUID();

        CyclicBarrier startingLine = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        try {
            for (UUID cartId : new UUID[] {cartA, cartB}) {
                executor.submit(() -> {
                    try {
                        startingLine.await();
                        checkoutService.checkoutIdempotently(cartId, key);
                        successes.incrementAndGet();
                    } catch (BusinessRuleViolation e) {
                        if ("idempotency_key_reused".equals(e.getCode())) {
                            conflicts.incrementAndGet();
                        } else {
                            throw new RuntimeException(e);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdown();
        }

        // Exactly one winner, one conflict — never both succeeding (two orders) and never both
        // failing.
        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);

        int skuADecremented = inventoryService.getBySku(skuA).getAvailableQuantity() == 4 ? 1 : 0;
        int skuBDecremented = inventoryService.getBySku(skuB).getAvailableQuantity() == 4 ? 1 : 0;
        assertThat(skuADecremented + skuBDecremented)
            .as("exactly one of the two carts' stock was actually reserved")
            .isEqualTo(1);
    }

    @Test
    void failedCheckoutAllowsRetryWithSameKeyOnceStockIsReplenished() {
        String skuA = newProductWithStockAndPrice(5, "10.00");
        String skuB = newProductWithStockAndPrice(0, "3.50");
        UUID cartId = cartService.createCart().getId();
        cartService.setItemQuantity(cartId, skuA, 2);
        cartService.setItemQuantity(cartId, skuB, 1);
        String key = "retry-after-failure-" + UUID.randomUUID();

        assertThatThrownBy(() -> checkoutService.checkoutIdempotently(cartId, key))
            .isInstanceOf(BusinessRuleViolation.class)
            .hasFieldOrPropertyWithValue("code", "insufficient_stock");

        assertThat(inventoryService.getBySku(skuA).getAvailableQuantity()).isEqualTo(5);
        assertThat(inventoryService.getBySku(skuB).getAvailableQuantity()).isEqualTo(0);

        restoreStockAndCommit(skuB, 5);

        CheckoutResult retry = checkoutService.checkoutIdempotently(cartId, key);

        assertThat(retry.created()).isTrue();
        assertThat(retry.order().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(inventoryService.getBySku(skuA).getAvailableQuantity()).isEqualTo(3);
        assertThat(inventoryService.getBySku(skuB).getAvailableQuantity()).isEqualTo(4);
    }
}

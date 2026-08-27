package com.commercecore.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercecore.AbstractIntegrationTest;
import com.commercecore.cart.CartService;
import com.commercecore.catalog.ProductService;
import com.commercecore.inventory.InventoryService;
import com.commercecore.reservation.InventoryReservation;
import com.commercecore.reservation.ReservationService;
import com.commercecore.reservation.ReservationStatus;
import com.commercecore.shared.BusinessRuleViolation;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves checkout preserves the reservation layer's concurrency guarantees rather than
 * reintroducing an oversell/deadlock risk on top of it.
 */
class CheckoutConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private CartService cartService;

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private ReservationService reservationService;

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

    private String newIdempotencyKey() {
        return "key-" + UUID.randomUUID();
    }

    @Test
    void concurrentCheckoutsForLastUnitOfStockProduceExactlyOneWinner() throws Exception {
        String sku = newProductWithStockAndPrice(1, "10.00");
        UUID cartA = newCartWithItem(sku, 1);
        UUID cartB = newCartWithItem(sku, 1);

        CyclicBarrier startingLine = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(2);

        try {
            for (UUID cartId : new UUID[] {cartA, cartB}) {
                executor.submit(() -> {
                    try {
                        startingLine.await();
                        checkoutService.checkoutIdempotently(cartId, newIdempotencyKey());
                        successes.incrementAndGet();
                    } catch (BusinessRuleViolation e) {
                        failures.incrementAndGet();
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

        assertThat(successes.get()).isEqualTo(1);
        assertThat(failures.get()).isEqualTo(1);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(0);
    }

    @Test
    void concurrentMultiSkuCheckoutsWithOppositeCartOrderingBothSucceed() throws Exception {
        String skuOne = newProductWithStockAndPrice(5, "10.00");
        String skuTwo = newProductWithStockAndPrice(5, "5.00");

        UUID cartA = cartService.createCart().getId();
        cartService.setItemQuantity(cartA, skuOne, 1);
        cartService.setItemQuantity(cartA, skuTwo, 1);

        UUID cartB = cartService.createCart().getId();
        cartService.setItemQuantity(cartB, skuTwo, 1);
        cartService.setItemQuantity(cartB, skuOne, 1);

        CyclicBarrier startingLine = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch done = new CountDownLatch(2);

        try {
            for (UUID cartId : new UUID[] {cartA, cartB}) {
                executor.submit(() -> {
                    try {
                        startingLine.await();
                        checkoutService.checkoutIdempotently(cartId, newIdempotencyKey());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            // A generous but bounded timeout: sorting cart lines by SKU before reserving means
            // both checkouts acquire inventory row locks in the same order, so this should
            // complete quickly. Without that ordering, a lock-ordering deadlock could otherwise
            // hang until PostgreSQL's own deadlock detector intervenes.
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdown();
        }

        assertThat(inventoryService.getBySku(skuOne).getAvailableQuantity()).isEqualTo(3);
        assertThat(inventoryService.getBySku(skuTwo).getAvailableQuantity()).isEqualTo(3);
    }

    @Test
    void checkoutCreatedReservationsAreAssociatedWithTheirOrder() {
        String skuA = newProductWithStockAndPrice(5, "10.00");
        String skuB = newProductWithStockAndPrice(5, "3.50");
        UUID cartId = cartService.createCart().getId();
        cartService.setItemQuantity(cartId, skuA, 2);
        cartService.setItemQuantity(cartId, skuB, 1);

        Order order = checkoutService.checkoutIdempotently(cartId, newIdempotencyKey()).order();

        List<InventoryReservation> reservations = reservationService.getReservationsForOrder(order.getId());
        assertThat(reservations).hasSize(2);
        assertThat(reservations).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(ReservationStatus.ACTIVE));
        assertThat(reservations).extracting(InventoryReservation::getSku).containsExactlyInAnyOrder(skuA, skuB);
    }
}

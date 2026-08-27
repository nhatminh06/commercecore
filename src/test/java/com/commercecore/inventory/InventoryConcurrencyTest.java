package com.commercecore.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercecore.AbstractIntegrationTest;
import com.commercecore.catalog.ProductService;
import com.commercecore.shared.BusinessRuleViolation;
import java.math.BigDecimal;
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
 * Proves the central Milestone 1 invariant: concurrent stock consumption cannot oversell.
 * Every attempt starts from the same point via a CyclicBarrier/CountDownLatch instead of relying
 * on sleeps, so the race is deterministic rather than incidental.
 */
class InventoryConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private InventoryService inventoryService;

    private String newProductWithStock(int quantity) {
        String sku = "SKU-" + UUID.randomUUID();
        productService.createProduct(sku, "Widget", new BigDecimal("9.99"), quantity);
        return sku;
    }

    @Test
    void lastItemRaceAllowsExactlyOneWinner() throws Exception {
        String sku = newProductWithStock(1);
        int attempts = 2;

        CyclicBarrier startingLine = new CyclicBarrier(attempts);
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(attempts);

        try {
            for (int i = 0; i < attempts; i++) {
                executor.submit(() -> {
                    try {
                        startingLine.await();
                        inventoryService.consume(sku, 1);
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
    void manyBuyersRaceNeverOversells() throws Exception {
        int initialStock = 10;
        int buyers = 100;
        String sku = newProductWithStock(initialStock);

        CyclicBarrier startingLine = new CyclicBarrier(buyers);
        ExecutorService executor = Executors.newFixedThreadPool(buyers);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(buyers);

        try {
            for (int i = 0; i < buyers; i++) {
                executor.submit(() -> {
                    try {
                        startingLine.await();
                        inventoryService.consume(sku, 1);
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

            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdown();
        }

        assertThat(successes.get()).isEqualTo(initialStock);
        assertThat(failures.get()).isEqualTo(buyers - initialStock);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(0);
    }
}

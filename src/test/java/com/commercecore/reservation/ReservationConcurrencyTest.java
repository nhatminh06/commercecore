package com.commercecore.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercecore.AbstractIntegrationTest;
import com.commercecore.catalog.ProductService;
import com.commercecore.inventory.InventoryService;
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
 * Proves reservations preserve Milestone 1's no-overselling invariant, and additionally that
 * concurrent state transitions on the same reservation cannot double-apply their inventory
 * effect. Every race starts from a synchronized point (CyclicBarrier) instead of sleeps.
 */
class ReservationConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private InventoryService inventoryService;

    private String newProductWithStock(int quantity) {
        String sku = "SKU-" + UUID.randomUUID();
        productService.createProduct(sku, "Widget", new BigDecimal("9.99"), quantity);
        return sku;
    }

    @Test
    void lastItemRaceAllowsExactlyOneReservationWinner() throws Exception {
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
                        reservationService.reserve(sku, 1);
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
                        reservationService.reserve(sku, 1);
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

    @Test
    void concurrentDoubleReleaseRestoresInventoryExactlyOnce() throws Exception {
        String sku = newProductWithStock(5);
        UUID reservationId = reservationService.reserve(sku, 2).getId();
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(3);

        int attempts = 2;
        CyclicBarrier startingLine = new CyclicBarrier(attempts);
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch done = new CountDownLatch(attempts);

        try {
            for (int i = 0; i < attempts; i++) {
                executor.submit(() -> {
                    try {
                        startingLine.await();
                        reservationService.release(reservationId);
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

        assertThat(reservationService.getReservation(reservationId).getStatus())
            .isEqualTo(ReservationStatus.RELEASED);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(5);
    }

    @Test
    void concurrentConfirmVersusReleaseLeavesExactlyOneConsistentOutcome() throws Exception {
        String sku = newProductWithStock(5);
        UUID reservationId = reservationService.reserve(sku, 2).getId();
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(3);

        CyclicBarrier startingLine = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch done = new CountDownLatch(2);

        try {
            executor.submit(() -> {
                try {
                    startingLine.await();
                    reservationService.confirm(reservationId);
                } catch (BusinessRuleViolation ignored) {
                    // one side of this race is expected to lose with a conflict
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
            executor.submit(() -> {
                try {
                    startingLine.await();
                    reservationService.release(reservationId);
                } catch (BusinessRuleViolation ignored) {
                    // one side of this race is expected to lose with a conflict
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdown();
        }

        ReservationStatus finalStatus = reservationService.getReservation(reservationId).getStatus();
        int finalInventory = inventoryService.getBySku(sku).getAvailableQuantity();

        assertThat(finalStatus).isIn(ReservationStatus.CONFIRMED, ReservationStatus.RELEASED);
        if (finalStatus == ReservationStatus.CONFIRMED) {
            assertThat(finalInventory).isEqualTo(3);
        } else {
            assertThat(finalInventory).isEqualTo(5);
        }
    }
}

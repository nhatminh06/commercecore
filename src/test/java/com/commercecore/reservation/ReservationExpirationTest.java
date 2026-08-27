package com.commercecore.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercecore.AbstractIntegrationTest;
import com.commercecore.catalog.ProductService;
import com.commercecore.inventory.InventoryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Expiration is tested by passing an explicit {@code now} to
 * {@link ReservationService#expireDueReservations(Instant)} — no sleeping, no fake clock, no
 * scheduler. Each reservation's real {@code expiresAt} (server time + fixed TTL) is read back
 * from the created reservation, and tests pick a {@code now} before/at/after that instant.
 *
 * <p>{@code expireDueReservations} is a genuine unscoped sweep (as it should be — a real
 * scheduler would call it once for the whole system), and {@link AbstractIntegrationTest} shares
 * one PostgreSQL container across the entire test suite with no per-test cleanup. So these tests
 * assert on the one reservation/SKU each test created, not on the sweep's aggregate returned
 * count, which can also pick up leftover ACTIVE reservations left behind by other test classes
 * that share the same fixed TTL and therefore a nearby {@code expires_at}.
 */
class ReservationExpirationTest extends AbstractIntegrationTest {

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
    void notYetExpiredReservationIsUntouched() {
        String sku = newProductWithStock(5);
        InventoryReservation reservation = reservationService.reserve(sku, 2);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(3);

        reservationService.expireDueReservations(reservation.getExpiresAt().minusSeconds(1));

        assertThat(reservationService.getReservation(reservation.getId()).getStatus())
            .isEqualTo(ReservationStatus.ACTIVE);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(3);
    }

    @Test
    void reservationExpiresExactlyAtTheBoundary() {
        String sku = newProductWithStock(5);
        InventoryReservation reservation = reservationService.reserve(sku, 2);

        reservationService.expireDueReservations(reservation.getExpiresAt());

        assertThat(reservationService.getReservation(reservation.getId()).getStatus())
            .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(5);
    }

    @Test
    void reservationExpiresAfterTheBoundary() {
        String sku = newProductWithStock(5);
        InventoryReservation reservation = reservationService.reserve(sku, 2);

        reservationService.expireDueReservations(reservation.getExpiresAt().plusSeconds(1));

        assertThat(reservationService.getReservation(reservation.getId()).getStatus())
            .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(5);
    }

    @Test
    void runningExpirationTwiceRestoresInventoryOnlyOnce() {
        String sku = newProductWithStock(5);
        InventoryReservation reservation = reservationService.reserve(sku, 2);
        Instant due = reservation.getExpiresAt().plusSeconds(1);

        reservationService.expireDueReservations(due);
        reservationService.expireDueReservations(due);

        assertThat(reservationService.getReservation(reservation.getId()).getStatus())
            .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(5);
    }

    @Test
    void confirmedReservationIsNeverExpired() {
        String sku = newProductWithStock(5);
        InventoryReservation reservation = reservationService.reserve(sku, 2);
        reservationService.confirm(reservation.getId());

        reservationService.expireDueReservations(reservation.getExpiresAt().plusSeconds(3600));

        assertThat(reservationService.getReservation(reservation.getId()).getStatus())
            .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(inventoryService.getBySku(sku).getAvailableQuantity()).isEqualTo(3);
    }
}

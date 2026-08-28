package com.commercecore.reservation;

import com.commercecore.inventory.InventoryRepository;
import com.commercecore.shared.BusinessRuleViolation;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reservations temporarily own stock; carts never do (see {@code docs/cart.md} and
 * {@code docs/reservations.md}). This service has no dependency on the cart module.
 */
@Service
public class ReservationService {

    private static final Duration RESERVATION_TTL = Duration.ofMinutes(15);

    private final ReservationRepository reservationRepository;
    private final InventoryRepository inventoryRepository;

    public ReservationService(ReservationRepository reservationRepository, InventoryRepository inventoryRepository) {
        this.reservationRepository = reservationRepository;
        this.inventoryRepository = inventoryRepository;
    }

    /**
     * Reuses the exact conditional UPDATE that proved Milestone 1's no-overselling invariant:
     * the decrement and the "is there enough left" check are one PostgreSQL statement, so
     * concurrent reservation attempts against the same SKU can't both act on the same
     * pre-decrement quantity. The decrement and the reservation INSERT happen in this one
     * @Transactional method, i.e. one database transaction: if the INSERT ever failed, the
     * whole transaction (including the decrement) rolls back — never a decrement with no
     * matching reservation.
     */
    @Transactional
    public InventoryReservation reserve(String sku, int quantity) {
        return reserve(sku, quantity, null);
    }

    /**
     * Same primitive as {@link #reserve(String, int)}, additionally tagging the created
     * reservation with the order that claimed it. Used by checkout so that a checkout-created
     * reservation can be found again (e.g. {@link #getReservationsForOrder}); reservations
     * created through the standalone API go through the two-arg overload and leave this null.
     */
    @Transactional
    public InventoryReservation reserve(String sku, int quantity, UUID orderId) {
        if (quantity <= 0) {
            throw new BusinessRuleViolation(HttpStatus.BAD_REQUEST, "invalid_quantity",
                "quantity must be positive");
        }

        int updated = inventoryRepository.tryConsume(sku, quantity);
        if (updated != 1) {
            if (!inventoryRepository.existsById(sku)) {
                throw new BusinessRuleViolation(HttpStatus.NOT_FOUND, "unknown_sku", "Unknown SKU: " + sku);
            }
            throw new BusinessRuleViolation(HttpStatus.CONFLICT, "insufficient_stock",
                "Not enough stock available for SKU: " + sku);
        }

        // Truncated to microseconds: PostgreSQL TIMESTAMPTZ only stores microsecond precision,
        // while Instant.now() carries nanoseconds. Without truncating here, the value read back
        // from the DB can differ from this in-memory Instant by a sub-microsecond rounding
        // remainder, which breaks the exact "expires_at <= now" boundary check in
        // expireDueReservations when a caller passes this exact expiresAt back as `now`.
        Instant expiresAt = Instant.now().truncatedTo(ChronoUnit.MICROS).plus(RESERVATION_TTL);
        InventoryReservation reservation = new InventoryReservation(UUID.randomUUID(), sku, quantity, expiresAt);
        reservation.setOrderId(orderId);
        return reservationRepository.save(reservation);
    }

    public InventoryReservation getReservation(UUID id) {
        return reservationRepository.findById(id)
            .orElseThrow(() -> new BusinessRuleViolation(HttpStatus.NOT_FOUND, "reservation_not_found",
                "Unknown reservation: " + id));
    }

    public List<InventoryReservation> getReservationsForOrder(UUID orderId) {
        return reservationRepository.findByOrderId(orderId);
    }

    /**
     * {@code true} only if every reservation for this order is still ACTIVE or CONFIRMED — i.e.
     * CommerceCore still genuinely owns the stock it claimed at checkout. A RELEASED or EXPIRED
     * reservation means that stock has already gone back to the available pool (possibly claimed
     * by someone else since), so any later "confirm this order" decision — whether from the normal
     * Kafka workflow or from payment reconciliation — must not proceed automatically once this
     * returns {@code false}. See {@code docs/order-workflow.md} and {@code docs/reconciliation.md}.
     */
    public boolean isOwnershipIntactForOrder(UUID orderId) {
        return getReservationsForOrder(orderId).stream()
            .noneMatch(r -> r.getStatus() == ReservationStatus.RELEASED || r.getStatus() == ReservationStatus.EXPIRED);
    }

    /**
     * ACTIVE -> CONFIRMED keeps the inventory decrement permanently: confirming means the stock
     * really was consumed. CONFIRMED -> CONFIRMED is an idempotent no-op (safe retry). Any other
     * starting status is a conflict — a released or expired reservation gave its stock back, so
     * confirming it now would consume stock a second time for the same reservation.
     */
    @Transactional
    public InventoryReservation confirm(UUID id) {
        InventoryReservation reservation = lockReservation(id);
        switch (reservation.getStatus()) {
            case ACTIVE -> reservation.setStatus(ReservationStatus.CONFIRMED);
            case CONFIRMED -> {
                // idempotent no-op
            }
            case RELEASED, EXPIRED -> throw invalidTransition(reservation, "confirm");
        }
        return reservationRepository.save(reservation);
    }

    /**
     * ACTIVE -> RELEASED restores the reserved quantity exactly once, inside the same
     * transaction as the status write and under the row lock acquired by
     * {@link #lockReservation}. RELEASED -> RELEASED is an idempotent no-op (does not restore
     * stock again). CONFIRMED/EXPIRED cannot be released.
     */
    @Transactional
    public InventoryReservation release(UUID id) {
        InventoryReservation reservation = lockReservation(id);
        switch (reservation.getStatus()) {
            case ACTIVE -> {
                reservation.setStatus(ReservationStatus.RELEASED);
                inventoryRepository.restore(reservation.getSku(), reservation.getQuantity());
            }
            case RELEASED -> {
                // idempotent no-op
            }
            case CONFIRMED, EXPIRED -> throw invalidTransition(reservation, "release");
        }
        return reservationRepository.save(reservation);
    }

    /**
     * Explicit expiration pass, not a background job (Milestone 3 proves the transition; a
     * scheduler is a later decision). Takes {@code now} as a parameter rather than reading a
     * clock internally, so callers (and tests) control exactly what "due" means without needing
     * to wait on or fake the system clock.
     *
     * <p>Candidates are found with a plain (non-locking) query, then each is re-locked and
     * re-checked individually before acting — the same status/expiry condition is verified again
     * under the row lock — so a reservation released or confirmed concurrently between the scan
     * and the lock is correctly left alone, and running this method twice over the same data
     * restores stock only once (the second pass's scan no longer finds an ACTIVE row to expire).
     */
    @Transactional
    public int expireDueReservations(Instant now) {
        List<InventoryReservation> candidates =
            reservationRepository.findByStatusAndExpiresAtLessThanEqual(ReservationStatus.ACTIVE, now);

        int expiredCount = 0;
        for (InventoryReservation candidate : candidates) {
            InventoryReservation locked = lockReservation(candidate.getId());
            if (locked.getStatus() == ReservationStatus.ACTIVE && !locked.getExpiresAt().isAfter(now)) {
                locked.setStatus(ReservationStatus.EXPIRED);
                inventoryRepository.restore(locked.getSku(), locked.getQuantity());
                reservationRepository.save(locked);
                expiredCount++;
            }
        }
        return expiredCount;
    }

    private InventoryReservation lockReservation(UUID id) {
        return reservationRepository.findByIdForUpdate(id)
            .orElseThrow(() -> new BusinessRuleViolation(HttpStatus.NOT_FOUND, "reservation_not_found",
                "Unknown reservation: " + id));
    }

    private BusinessRuleViolation invalidTransition(InventoryReservation reservation, String operation) {
        return new BusinessRuleViolation(HttpStatus.CONFLICT, "invalid_reservation_transition",
            "Cannot " + operation + " a reservation in status " + reservation.getStatus());
    }
}

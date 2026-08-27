package com.commercecore.reservation;

import java.time.Instant;
import java.util.UUID;

public record ReservationResponse(UUID id, String sku, int quantity, ReservationStatus status, Instant expiresAt) {

    static ReservationResponse from(InventoryReservation reservation) {
        return new ReservationResponse(reservation.getId(), reservation.getSku(), reservation.getQuantity(),
            reservation.getStatus(), reservation.getExpiresAt());
    }
}

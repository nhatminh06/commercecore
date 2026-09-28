package com.commercecore.reservation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateReservationRequest(@NotBlank @Size(max = 64) String sku,
    @Max(100) int quantity) {
}

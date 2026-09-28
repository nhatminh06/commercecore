package com.commercecore.inventory;
import jakarta.validation.constraints.Max;

public record ConsumeInventoryRequest(@Max(100) int quantity) {
}

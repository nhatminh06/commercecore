package com.commercecore.cart;
import jakarta.validation.constraints.Max;

public record SetCartItemRequest(@Max(100) int quantity) {
}

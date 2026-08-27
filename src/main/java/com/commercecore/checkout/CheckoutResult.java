package com.commercecore.checkout;

/**
 * {@code created} distinguishes a fresh checkout execution from an idempotent replay, so the
 * controller can return 201 for the former and 200 for the latter — the order representation
 * itself is identical either way.
 */
public record CheckoutResult(Order order, boolean created) {
}

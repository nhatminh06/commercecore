package com.commercecore.checkout;

/**
 * PENDING means payment/workflow outcome is unresolved. CONFIRMED means a payment authorization
 * has been applied and the order's held inventory is permanently confirmed. CANCELLED means a
 * payment failure has been applied and compensated (reservations released, stock restored). Both
 * CONFIRMED and CANCELLED are terminal — see {@code OrderPaymentWorkflowService} for the legal
 * transition table. No PAID/SHIPPED/FULFILLING/REFUNDED/RETURNED yet: those correspond to
 * workflows CommerceCore doesn't implement.
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    CANCELLED
}

package com.commercecore.payment;

/**
 * PENDING, AUTHORIZED, and FAILED are unsurprising. UNKNOWN exists specifically for the case a
 * timeout leaves ambiguous: CommerceCore asked the provider to authorize, and cannot currently
 * prove whether that succeeded or failed provider-side. UNKNOWN is not a failure — see
 * {@code docs/payments.md}. AUTHORIZED and FAILED are terminal for this milestone; UNKNOWN is
 * left unresolved (no reconciliation yet).
 */
public enum PaymentStatus {
    PENDING,
    AUTHORIZED,
    FAILED,
    UNKNOWN
}

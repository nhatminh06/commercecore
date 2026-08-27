package com.commercecore.payment;

/**
 * Kept intentionally small — the two outcomes {@link FakePaymentProvider} can produce.
 * REFUNDED/CAPTURED/CHARGEBACK/DISPUTED do not exist yet because nothing in CommerceCore
 * generates or needs them yet.
 */
public enum WebhookEventType {
    PAYMENT_AUTHORIZED,
    PAYMENT_DECLINED
}

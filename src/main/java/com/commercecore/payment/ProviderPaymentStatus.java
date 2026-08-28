package com.commercecore.payment;

/**
 * What the provider reports for a past request, as returned by {@link PaymentProvider#lookup}.
 * Deliberately not {@link PaymentStatus}: provider truth and CommerceCore's own payment state are
 * separate concepts that happen to often align, not the same enum wearing two names. There is no
 * {@code PENDING}/{@code UNKNOWN} here — a real provider either knows what happened to a request
 * it received, or has no record of it ({@code NOT_FOUND}); it never reports its own internal
 * ambiguity back to the caller the way CommerceCore's {@code UNKNOWN} models CommerceCore's own.
 */
public enum ProviderPaymentStatus {
    AUTHORIZED,
    DECLINED,
    NOT_FOUND
}

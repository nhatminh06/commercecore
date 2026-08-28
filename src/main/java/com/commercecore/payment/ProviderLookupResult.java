package com.commercecore.payment;

/**
 * The result of a read-only {@link PaymentProvider#lookup} call. {@code providerReference} is
 * only ever present alongside {@link ProviderPaymentStatus#AUTHORIZED} — a decline or an unknown
 * request has no provider-side reference to report.
 */
public record ProviderLookupResult(ProviderPaymentStatus status, String providerReference) {
}

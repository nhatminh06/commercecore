package com.commercecore.payment;

/**
 * What the provider can tell CommerceCore synchronously. A timeout is deliberately not a variant
 * here — see {@link PaymentProviderTimeoutException} — because a lost response is not a result
 * the provider gave us; it's the absence of one.
 */
public sealed interface AuthorizationResult {

    record Authorized(String providerReference) implements AuthorizationResult {
    }

    record Declined(String reason) implements AuthorizationResult {
    }
}

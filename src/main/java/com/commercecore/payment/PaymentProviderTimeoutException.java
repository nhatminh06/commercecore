package com.commercecore.payment;

/**
 * Thrown when a provider call's outcome could not be observed — the request may or may not have
 * been processed provider-side. This is CommerceCore's own uncertainty, not a provider-reported
 * failure; catching this must never be treated as proof of failure. See {@code docs/payments.md}.
 */
public class PaymentProviderTimeoutException extends RuntimeException {

    public PaymentProviderTimeoutException(String message) {
        super(message);
    }

    public PaymentProviderTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}

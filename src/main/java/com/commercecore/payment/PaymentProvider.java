package com.commercecore.payment;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The external-system boundary this milestone studies. {@code providerRequestId} is CommerceCore's
 * own payment ID, passed as the stable request identity — not regenerated per call — so that a
 * provider implementing its own idempotency (as {@link FakePaymentProvider} does) can recognize a
 * repeated request for the same logical payment instead of processing it again.
 */
public interface PaymentProvider {

    /**
     * @throws PaymentProviderTimeoutException if the outcome could not be observed
     */
    AuthorizationResult authorize(UUID providerRequestId, BigDecimal amount);

    /**
     * Read-only: asks the provider what actually happened to a request it may have already
     * received, without ever authorizing, retrying, or otherwise mutating provider-side state.
     * This is the entire mechanism reconciliation uses to resolve an ambiguous local {@code
     * UNKNOWN} payment — see {@code docs/reconciliation.md}. Never throws for "not found"; that is
     * a legitimate, distinct outcome ({@link ProviderPaymentStatus#NOT_FOUND}), not an error.
     */
    ProviderLookupResult lookup(UUID providerRequestId);
}

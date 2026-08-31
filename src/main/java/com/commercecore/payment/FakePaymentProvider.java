package com.commercecore.payment;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

/**
 * Narrow local-profile test double retained so existing domain tests need not open a TCP socket.
 * Normal runtime uses {@link GrpcPaymentProviderClient}; the extracted Payment Service is the
 * persistent simulated external system.
 */
@Component
@Profile("local-provider")
public class FakePaymentProvider implements PaymentProvider {

    public enum NextOutcome {
        SUCCESS,
        DECLINED,
        TIMEOUT_AFTER_PROCESSING,
        // Symmetric to TIMEOUT_AFTER_PROCESSING: the provider actually declined the request, but
        // the response describing that decline was lost before CommerceCore ever saw it. Exists
        // so reconciliation's "local UNKNOWN, provider truth DECLINED" path can be exercised
        // realistically instead of contriving an unreachable local state — see
        // docs/reconciliation.md.
        TIMEOUT_AFTER_DECLINE
    }

    private volatile NextOutcome nextOutcome = NextOutcome.SUCCESS;
    private final AtomicInteger authorizationCallCount = new AtomicInteger();
    private final AtomicInteger lookupCallCount = new AtomicInteger();

    // Represents the provider's own ground truth, independent of what CommerceCore managed to
    // observe. A TIMEOUT_AFTER_PROCESSING/TIMEOUT_AFTER_DECLINE outcome still records an entry
    // here — that's the whole point: the provider really did process the request even though the
    // caller never found out. lookup() reads these maps and never writes to providerSideAuthorizations
    // /providerSideDeclines except from authorize() itself.
    private final Map<UUID, String> providerSideAuthorizations = new ConcurrentHashMap<>();
    private final Set<UUID> providerSideDeclines = ConcurrentHashMap.newKeySet();

    public void nextOutcome(NextOutcome outcome) {
        this.nextOutcome = outcome;
    }

    public int callCount() {
        return authorizationCallCount.get();
    }

    public int lookupCallCount() {
        return lookupCallCount.get();
    }

    /**
     * Test-only introspection proving the provider genuinely processed a request even when the
     * caller received {@link PaymentProviderTimeoutException} — the concrete evidence behind
     * "timeout does not mean failure."
     */
    public boolean hasProviderSideAuthorization(UUID providerRequestId) {
        return providerSideAuthorizations.containsKey(providerRequestId);
    }

    /**
     * The provider-side reference for a request the provider actually processed, or {@code null}
     * if it never did. Lets a test (or the manual demo flow) recover the reference a
     * {@code TIMEOUT_AFTER_PROCESSING} call never returned to CommerceCore, in order to build a
     * realistic webhook payload — this is exactly the piece of information CommerceCore itself
     * doesn't have, which is the entire premise of the UNKNOWN state.
     */
    public String getProviderSideReference(UUID providerRequestId) {
        return providerSideAuthorizations.get(providerRequestId);
    }

    @Override
    public AuthorizationResult authorize(UUID providerRequestId, BigDecimal amount) {
        authorizationCallCount.incrementAndGet();

        String existingReference = providerSideAuthorizations.get(providerRequestId);
        if (existingReference != null) {
            // Provider-side idempotency: the same request identity, seen again, returns the
            // already-processed result rather than authorizing a second time.
            return new AuthorizationResult.Authorized(existingReference);
        }
        if (providerSideDeclines.contains(providerRequestId)) {
            return new AuthorizationResult.Declined("simulated_decline");
        }

        return switch (nextOutcome) {
            case SUCCESS -> {
                String reference = "pay_" + UUID.randomUUID();
                providerSideAuthorizations.put(providerRequestId, reference);
                yield new AuthorizationResult.Authorized(reference);
            }
            case DECLINED -> {
                providerSideDeclines.add(providerRequestId);
                yield new AuthorizationResult.Declined("simulated_decline");
            }
            case TIMEOUT_AFTER_PROCESSING -> {
                String reference = "pay_" + UUID.randomUUID();
                providerSideAuthorizations.put(providerRequestId, reference);
                throw new PaymentProviderTimeoutException(
                    "simulated timeout: provider processed the request but the response was lost");
            }
            case TIMEOUT_AFTER_DECLINE -> {
                providerSideDeclines.add(providerRequestId);
                throw new PaymentProviderTimeoutException(
                    "simulated timeout: provider declined the request but the response was lost");
            }
        };
    }

    @Override
    public ProviderLookupResult lookup(UUID providerRequestId) {
        lookupCallCount.incrementAndGet();

        String reference = providerSideAuthorizations.get(providerRequestId);
        if (reference != null) {
            return new ProviderLookupResult(ProviderPaymentStatus.AUTHORIZED, reference);
        }
        if (providerSideDeclines.contains(providerRequestId)) {
            return new ProviderLookupResult(ProviderPaymentStatus.DECLINED, null);
        }
        return new ProviderLookupResult(ProviderPaymentStatus.NOT_FOUND, null);
    }
}

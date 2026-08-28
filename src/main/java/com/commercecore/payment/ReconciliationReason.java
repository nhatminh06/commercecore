package com.commercecore.payment;

/**
 * A bounded, explicit set of reasons a reconciliation case is {@code OPEN} or
 * {@code REQUIRES_REVIEW} — never free-form stack-trace text as business state. {@code null} is
 * used for a plain {@code RESOLVED} case (nothing to explain; provider truth simply confirmed or
 * was safely applied).
 */
public enum ReconciliationReason {
    /** Provider has no record of this request — not proof of failure; see Invariant 8. */
    PROVIDER_NOT_FOUND,
    /** Provider confirms AUTHORIZED, but a reservation for the order is RELEASED/EXPIRED. */
    AUTHORIZED_WITHOUT_RESERVED_STOCK,
    /** Local payment is AUTHORIZED; provider now reports DECLINED. */
    LOCAL_AUTHORIZED_PROVIDER_DECLINED,
    /** Local payment is FAILED; provider now reports AUTHORIZED. */
    LOCAL_FAILED_PROVIDER_AUTHORIZED,
    /** Local and provider both say AUTHORIZED, but their provider references disagree. */
    PROVIDER_REFERENCE_MISMATCH
}

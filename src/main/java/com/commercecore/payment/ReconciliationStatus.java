package com.commercecore.payment;

/**
 * OPEN: provider truth is still unresolved (e.g. {@code NOT_FOUND}) — retry reconciliation later.
 * RESOLVED: provider truth became definitive and CommerceCore safely applied or confirmed it.
 * REQUIRES_REVIEW: provider truth is definitive, but automatic business repair is unsafe or
 * contradicts what CommerceCore already recorded — durable evidence for a human/future policy,
 * never guessed through automatically. See {@code docs/reconciliation.md}.
 */
public enum ReconciliationStatus {
    OPEN,
    RESOLVED,
    REQUIRES_REVIEW
}

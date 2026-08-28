package com.commercecore.outbox;

/**
 * Kept to the events that correspond to real, already-implemented domain facts. No
 * ORDER_UPDATED/DATA_CHANGED-style catch-all, and no event for a state CommerceCore doesn't
 * actually reach yet (e.g. nothing for UNKNOWN — see docs/outbox.md).
 */
public enum OutboxEventType {
    ORDER_CREATED,
    PAYMENT_AUTHORIZED,
    PAYMENT_FAILED,
    ORDER_CONFIRMED,
    ORDER_CANCELLED
}

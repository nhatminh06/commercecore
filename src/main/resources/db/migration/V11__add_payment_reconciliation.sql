-- Durable evidence that CommerceCore queried the payment provider's authoritative state for a
-- payment and either safely applied it or found automatic repair unsafe. One row per payment is
-- enough for this milestone — not a history table.
CREATE TABLE payment_reconciliation_cases (
    id                    UUID PRIMARY KEY,
    payment_id            UUID NOT NULL UNIQUE,
    status                VARCHAR(32) NOT NULL CHECK (status IN ('OPEN', 'RESOLVED', 'REQUIRES_REVIEW')),
    last_provider_status  VARCHAR(32) NULL CHECK (last_provider_status IN ('AUTHORIZED', 'DECLINED', 'NOT_FOUND')),
    reason                VARCHAR(64) NULL,
    attempt_count         INTEGER NOT NULL DEFAULT 0,
    last_checked_at       TIMESTAMPTZ NULL,
    created_at            TIMESTAMPTZ NOT NULL,
    resolved_at           TIMESTAMPTZ NULL,

    FOREIGN KEY (payment_id) REFERENCES payments (id)
);

CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY,
    aggregate_type VARCHAR(32) NOT NULL CHECK (aggregate_type IN ('ORDER', 'PAYMENT')),
    aggregate_id   UUID NOT NULL,
    event_type     VARCHAR(64) NOT NULL CHECK (event_type IN ('ORDER_CREATED', 'PAYMENT_AUTHORIZED', 'PAYMENT_FAILED')),
    payload        JSONB NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    published_at   TIMESTAMPTZ NULL,
    attempt_count  INTEGER NOT NULL DEFAULT 0,
    claimed_at     TIMESTAMPTZ NULL,
    claim_token    UUID NULL
);

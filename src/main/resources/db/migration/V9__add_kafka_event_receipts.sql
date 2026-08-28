CREATE TABLE kafka_event_receipts (
    event_id       UUID PRIMARY KEY,
    event_type     VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id   UUID NOT NULL,
    consumed_at    TIMESTAMPTZ NOT NULL
);

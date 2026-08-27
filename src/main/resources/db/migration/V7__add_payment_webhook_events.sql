CREATE TABLE payment_webhook_events (
    provider_event_id  VARCHAR(255) PRIMARY KEY,
    payment_id         UUID NOT NULL REFERENCES payments (id),
    event_type         VARCHAR(32) NOT NULL CHECK (event_type IN ('PAYMENT_AUTHORIZED', 'PAYMENT_DECLINED')),
    provider_reference VARCHAR(255) NULL,
    received_at        TIMESTAMPTZ NOT NULL
);

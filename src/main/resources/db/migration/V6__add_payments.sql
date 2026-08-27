CREATE TABLE payments (
    id                 UUID PRIMARY KEY,
    order_id           UUID NOT NULL UNIQUE REFERENCES orders (id),
    amount             NUMERIC(12, 2) NOT NULL CHECK (amount >= 0),
    status             VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'AUTHORIZED', 'FAILED', 'UNKNOWN')),
    provider_reference VARCHAR(255) NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL
);

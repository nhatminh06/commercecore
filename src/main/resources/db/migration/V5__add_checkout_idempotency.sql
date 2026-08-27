CREATE TABLE checkout_idempotency (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    cart_id         UUID NOT NULL REFERENCES carts (id),
    order_id        UUID NOT NULL REFERENCES orders (id),
    created_at      TIMESTAMPTZ NOT NULL
);

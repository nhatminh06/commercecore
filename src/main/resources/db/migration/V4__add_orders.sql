CREATE TABLE orders (
    id UUID PRIMARY KEY,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING')),
    total_amount NUMERIC(12, 2) NOT NULL CHECK (total_amount >= 0),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE order_items (
    order_id UUID NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    sku VARCHAR(64) NOT NULL REFERENCES products (sku),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(12, 2) NOT NULL CHECK (unit_price >= 0),
    line_total NUMERIC(12, 2) NOT NULL CHECK (line_total >= 0),
    PRIMARY KEY (order_id, sku)
);

-- Nullable and unconstrained by NOT NULL: reservations created through the standalone
-- /api/reservations API are not tied to any order and leave this null.
ALTER TABLE inventory_reservations
    ADD COLUMN order_id UUID NULL REFERENCES orders (id);

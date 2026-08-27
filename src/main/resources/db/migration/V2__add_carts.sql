CREATE TABLE carts (
    id UUID PRIMARY KEY
);

CREATE TABLE cart_items (
    cart_id UUID NOT NULL REFERENCES carts (id) ON DELETE CASCADE,
    sku VARCHAR(64) NOT NULL REFERENCES products (sku),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (cart_id, sku)
);

CREATE TABLE products (
    id BIGSERIAL PRIMARY KEY,
    sku VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    price_amount NUMERIC(12, 2) NOT NULL CHECK (price_amount >= 0)
);

CREATE TABLE inventory (
    sku VARCHAR(64) PRIMARY KEY REFERENCES products (sku),
    available_quantity INTEGER NOT NULL CHECK (available_quantity >= 0)
);

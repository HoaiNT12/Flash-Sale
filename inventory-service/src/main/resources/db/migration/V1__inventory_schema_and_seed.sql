CREATE TABLE products (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    unit_price NUMERIC(19, 2) NOT NULL CHECK (unit_price > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    available_stock INTEGER NOT NULL CHECK (available_stock >= 0),
    sale_starts_at TIMESTAMPTZ NOT NULL,
    sale_ends_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (sale_ends_at > sale_starts_at)
);

CREATE TABLE reservations (
    order_id UUID PRIMARY KEY,
    product_id UUID REFERENCES products(id) ON DELETE RESTRICT,
    quantity INTEGER CHECK (quantity > 0),
    unit_price NUMERIC(19, 2) CHECK (unit_price > 0),
    currency VARCHAR(3),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'RESERVED', 'CONFIRMED', 'CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (status IN ('PENDING', 'CANCELLED') OR
           (product_id IS NOT NULL AND quantity IS NOT NULL AND unit_price IS NOT NULL AND currency IS NOT NULL))
);

CREATE INDEX products_catalog_idx ON products (name, id);
CREATE INDEX reservations_product_status_idx ON reservations (product_id, status);

INSERT INTO products (id, name, unit_price, currency, available_stock, sale_starts_at, sale_ends_at) VALUES
    ('11111111-1111-1111-1111-111111111111', 'Flash Sale Keyboard', 99000, 'VND', 100,
     CURRENT_TIMESTAMP - INTERVAL '365 days', CURRENT_TIMESTAMP + INTERVAL '365 days'),
    ('22222222-2222-2222-2222-222222222222', 'Flash Sale Headphones', 199000, 'VND', 50,
     CURRENT_TIMESTAMP - INTERVAL '365 days', CURRENT_TIMESTAMP + INTERVAL '365 days'),
    ('33333333-3333-3333-3333-333333333333', 'Sold Out Mouse', 59000, 'VND', 0,
     CURRENT_TIMESTAMP - INTERVAL '365 days', CURRENT_TIMESTAMP + INTERVAL '365 days');

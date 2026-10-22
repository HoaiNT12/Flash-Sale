CREATE TABLE orders (
 id UUID PRIMARY KEY, customer_id VARCHAR(100) NOT NULL, idempotency_key VARCHAR(128) NOT NULL,
 product_id UUID NOT NULL, quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 5),
 unit_price NUMERIC(19,2), amount NUMERIC(19,2), currency VARCHAR(3),
 status VARCHAR(20) NOT NULL CHECK (status IN ('ACCEPTED','COMPENSATING','COMPLETED','REJECTED')),
 step VARCHAR(20) NOT NULL CHECK (step IN ('RESERVE','PAY','CONFIRM','VOID_PAYMENT','RELEASE','FINISHED')),
 attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0), deadline TIMESTAMPTZ NOT NULL,
 next_attempt TIMESTAMPTZ NOT NULL DEFAULT now(), lease_token UUID, lease_until TIMESTAMPTZ,
 failure_code VARCHAR(100), created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(customer_id, idempotency_key), CHECK (amount IS NULL OR amount >= 0), CHECK (unit_price IS NULL OR unit_price >= 0),
 CHECK (step NOT IN ('PAY','CONFIRM') OR (amount IS NOT NULL AND currency IS NOT NULL)),
 CHECK ((step = 'FINISHED') = (status IN ('COMPLETED','REJECTED')))
);
CREATE INDEX orders_due_idx ON orders(next_attempt, created_at) WHERE step <> 'FINISHED';
CREATE INDEX orders_customer_idx ON orders(customer_id, created_at DESC);
CREATE TABLE order_history (
 id BIGSERIAL PRIMARY KEY, order_id UUID NOT NULL REFERENCES orders(id),
 step VARCHAR(20) NOT NULL, event VARCHAR(60) NOT NULL, detail VARCHAR(100), created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX order_history_order_idx ON order_history(order_id, id);

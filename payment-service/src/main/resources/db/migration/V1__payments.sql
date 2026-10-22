CREATE TABLE wallets (
    customer_id VARCHAR(100) PRIMARY KEY,
    balance NUMERIC(19,2) NOT NULL CHECK (balance >= 0),
    currency CHAR(3) NOT NULL DEFAULT 'VND' CHECK (currency = 'VND'),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE payments (
    order_id UUID PRIMARY KEY,
    customer_id VARCHAR(100) REFERENCES wallets(customer_id),
    amount NUMERIC(19,2),
    currency CHAR(3),
    status VARCHAR(12) NOT NULL CHECK (status IN ('PENDING', 'CHARGED', 'VOIDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((customer_id IS NULL AND amount IS NULL AND currency IS NULL AND status <> 'CHARGED')
        OR (customer_id IS NOT NULL AND amount IS NOT NULL AND currency IS NOT NULL AND amount > 0 AND currency = 'VND'))
);

CREATE TABLE payment_ledger (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES payments(order_id),
    customer_id VARCHAR(100) NOT NULL REFERENCES wallets(customer_id),
    event_type VARCHAR(10) NOT NULL CHECK (event_type IN ('DEBIT', 'REFUND')),
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency CHAR(3) NOT NULL CHECK (currency = 'VND'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (order_id, event_type)
);
CREATE INDEX idx_payment_ledger_customer_time ON payment_ledger(customer_id, created_at);
CREATE INDEX idx_payments_customer_time ON payments(customer_id, created_at);

INSERT INTO wallets(customer_id, balance) VALUES ('alice', 100000000.00), ('bob', 100000000.00), ('poor', 0.00);

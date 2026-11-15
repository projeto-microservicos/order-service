CREATE TABLE order_outbox (
    id VARCHAR(36) PRIMARY KEY,
    payload TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    lease_token VARCHAR(36),
    attempts INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX order_outbox_pending_idx ON order_outbox(published_at, available_at);

CREATE TABLE order_payments (
    order_id VARCHAR(36) PRIMARY KEY REFERENCES orders(id),
    attempt_id VARCHAR(36) NOT NULL UNIQUE,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    intent_id VARCHAR(255) UNIQUE
);

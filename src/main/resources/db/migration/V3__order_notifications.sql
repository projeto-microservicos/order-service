-- BOT-03: one row per OrderCreated event processed by OrderConfirmationWorker.
-- event_id is the primary key: a redelivered event (at-least-once) finds its row and is a no-op.
CREATE TABLE order_notifications (
    event_id     VARCHAR(36)              PRIMARY KEY,
    order_id     VARCHAR(36)              NOT NULL,
    account_id   VARCHAR(36)              NOT NULL,
    currency     VARCHAR(3)               NOT NULL,
    total        NUMERIC(19, 2)           NOT NULL,
    ordered_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX order_notifications_order_idx ON order_notifications(order_id);

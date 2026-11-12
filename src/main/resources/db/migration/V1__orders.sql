CREATE TABLE orders (
    id VARCHAR(36) PRIMARY KEY,
    account_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    total NUMERIC(19,2) NOT NULL CHECK (total >= 0)
);
CREATE INDEX orders_account_date_idx ON orders(account_id,created_at DESC);
CREATE TABLE order_items (
    order_id VARCHAR(36) NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    item_position INTEGER NOT NULL,
    item_id VARCHAR(36) NOT NULL UNIQUE,
    product_id VARCHAR(36) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(19,2) NOT NULL CHECK (unit_price >= 0),
    total NUMERIC(19,2) NOT NULL CHECK (total >= 0),
    PRIMARY KEY(order_id,item_position)
);

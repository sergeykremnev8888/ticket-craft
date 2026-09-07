CREATE TABLE orders
(
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT          NOT NULL,
    event_id    UUID            NOT NULL,
    ticket_id   UUID            NOT NULL,
    total_price NUMERIC(19, 2)  NOT NULL,
    status      VARCHAR(32)     NOT NULL,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_orders_user_id
    ON orders (user_id);

CREATE INDEX idx_orders_ticket_id
    ON orders (ticket_id);

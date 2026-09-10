CREATE TABLE payments
(
    id           UUID          NOT NULL,
    order_id     BIGINT        NOT NULL,
    user_id      BIGINT        NOT NULL,
    amount       NUMERIC(19, 2) NOT NULL,
    status       VARCHAR(32)   NOT NULL,
    message_id   VARCHAR(128)  NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_payments PRIMARY KEY (id),
    CONSTRAINT uq_payments_order_id UNIQUE (order_id)
);

CREATE INDEX idx_payments_user_id
    ON payments (user_id);
CREATE UNIQUE INDEX uq_payments_message_id
    ON payments(message_id);
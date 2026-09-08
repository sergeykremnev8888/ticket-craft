CREATE TABLE idempotency_keys
(
    idempotency_key VARCHAR(128) NOT NULL,
    user_id         BIGINT       NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    order_id        BIGINT,
    status          VARCHAR(32)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_idempotency_keys
        PRIMARY KEY (idempotency_key)
);

CREATE INDEX idx_idempotency_keys_order_id
    ON idempotency_keys (order_id);

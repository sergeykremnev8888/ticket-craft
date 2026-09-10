CREATE TABLE order_sagas
(
    id          UUID        NOT NULL,
    order_id    BIGINT      NOT NULL,
    status      VARCHAR(64) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_order_sagas
        PRIMARY KEY (id),

    CONSTRAINT uk_order_sagas_order_id
        UNIQUE (order_id),

    CONSTRAINT fk_order_sagas_order
        FOREIGN KEY (order_id)
        REFERENCES orders (id)
);

CREATE INDEX idx_order_sagas_status
    ON order_sagas (status);

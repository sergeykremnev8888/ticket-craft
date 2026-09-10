CREATE TABLE payment_outbox (
    id UUID NOT NULL,
    message_id VARCHAR(128) NOT NULL,
    order_id BIGINT NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,

    CONSTRAINT pk_payment_outbox PRIMARY KEY (id),
    CONSTRAINT uq_payment_outbox_message_id UNIQUE (message_id)
);

CREATE INDEX idx_payment_outbox_unpublished
    ON payment_outbox (created_at)
    WHERE published_at IS NULL;

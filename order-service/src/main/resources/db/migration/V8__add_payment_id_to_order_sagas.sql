ALTER TABLE order_sagas
    ADD COLUMN payment_id UUID;

CREATE INDEX idx_order_sagas_payment_id
    ON order_sagas (payment_id);

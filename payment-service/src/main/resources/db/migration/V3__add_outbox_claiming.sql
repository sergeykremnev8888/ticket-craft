ALTER TABLE payment_outbox
    ADD COLUMN claimed_at TIMESTAMPTZ,
    ADD COLUMN claimed_by VARCHAR(128);

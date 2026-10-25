DROP INDEX IF EXISTS idx_payment_outbox_unpublished;

ALTER TABLE payment_outbox
    DROP COLUMN published_at,
    DROP COLUMN claimed_at,
    DROP COLUMN claimed_by;

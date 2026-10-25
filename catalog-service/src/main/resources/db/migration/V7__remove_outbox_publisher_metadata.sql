DROP INDEX IF EXISTS idx_outbox_events_pending;
DROP INDEX IF EXISTS idx_outbox_events_claim_id;

ALTER TABLE outbox_events
    DROP COLUMN status,
    DROP COLUMN published_at,
    DROP COLUMN attempts,
    DROP COLUMN next_attempt_at,
    DROP COLUMN locked_at,
    DROP COLUMN locked_by,
    DROP COLUMN claim_id;

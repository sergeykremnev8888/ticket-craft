ALTER TABLE outbox_events
    ADD COLUMN topic VARCHAR(128);

UPDATE outbox_events
SET topic = 'order-events'
WHERE topic IS NULL;

ALTER TABLE outbox_events
    ALTER COLUMN topic SET NOT NULL;

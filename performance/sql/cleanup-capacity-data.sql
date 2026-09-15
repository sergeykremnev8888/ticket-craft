-- Run only after all capacity runs have been reviewed and only when no capacity saga is in flight.
BEGIN;
DELETE FROM tickets WHERE event_id='10000000-0000-0000-0000-000000000003';
DELETE FROM events WHERE id='10000000-0000-0000-0000-000000000003';
COMMIT;

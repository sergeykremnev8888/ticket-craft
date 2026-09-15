SELECT status, COUNT(*) AS tickets
FROM tickets
WHERE event_id='10000000-0000-0000-0000-000000000003'
GROUP BY status ORDER BY status;

SELECT COUNT(*) AS invalid_capacity_ticket_rows
FROM tickets
WHERE event_id='10000000-0000-0000-0000-000000000003'
  AND ((status='AVAILABLE' AND (reservation_id IS NOT NULL OR reserved_until IS NOT NULL))
    OR (status='RESERVED' AND (reservation_id IS NULL OR reserved_until IS NULL))
    OR (status='SOLD' AND reservation_id IS NULL));

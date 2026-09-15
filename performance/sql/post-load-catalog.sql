-- Stage 2 catalog verification. Ticket #1 belongs to smoke; #2+ belong to load tests.
SELECT status, COUNT(*) AS tickets
FROM tickets
WHERE event_id = '10000000-0000-0000-0000-000000000001'
GROUP BY status
ORDER BY status;

SELECT COUNT(*) AS invalid_catalog_rows
FROM tickets
WHERE event_id = '10000000-0000-0000-0000-000000000001'
  AND (
      (status = 'SOLD' AND (reservation_id IS NULL OR reserved_until IS NOT NULL))
      OR
      (status = 'AVAILABLE' AND (reservation_id IS NOT NULL OR reserved_until IS NOT NULL))
  );

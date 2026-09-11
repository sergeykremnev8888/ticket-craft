-- Production event list:
--
-- ORDER BY event_date ASC, id ASC
CREATE INDEX idx_events_event_date_id
    ON events (event_date, id);


-- Reservation TTL cleanup:
--
-- WHERE status = 'RESERVED'
--   AND reserved_until < ?
--
-- Partial index содержит только реально зарезервированные tickets.
CREATE INDEX idx_tickets_reserved_until
    ON tickets (reserved_until)
    WHERE status = 'RESERVED';


-- uq_tickets_event_seat уже создаёт B-tree:
--
-- (event_id, seat_number)
--
-- Поэтому отдельный index только на event_id избыточен:
-- PostgreSQL может использовать левый prefix unique index.
DROP INDEX IF EXISTS idx_tickets_event_id;

CREATE INDEX idx_tickets_event_id
    ON tickets (event_id);

CREATE INDEX idx_tickets_event_status
    ON tickets (event_id, status);

CREATE INDEX idx_tickets_available
    ON tickets (event_id, id)
    WHERE status = 'AVAILABLE';

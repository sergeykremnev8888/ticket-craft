BEGIN;
INSERT INTO events (id, title, description, event_date, venue, created_at, updated_at)
VALUES ('10000000-0000-0000-0000-000000000003', 'TicketCraft k6 Capacity Event',
        'Synthetic event used only by issue #20 Stage 3 constant-rate capacity tests',
        CURRENT_TIMESTAMP + INTERVAL '30 days', 'Performance Test Venue', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO UPDATE SET title=EXCLUDED.title, description=EXCLUDED.description,
    event_date=EXCLUDED.event_date, venue=EXCLUDED.venue, updated_at=CURRENT_TIMESTAMP;

INSERT INTO tickets (id,event_id,seat_number,price,status,reservation_id,reserved_until,version,created_at,updated_at)
SELECT ('40000000-0000-0000-0000-' || lpad(to_hex(n),12,'0'))::uuid,
       '10000000-0000-0000-0000-000000000003'::uuid,
       'CAPACITY-' || lpad(n::text,5,'0'), 1500.00, 'AVAILABLE', NULL, NULL, 0,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM generate_series(1,60000) AS n
ON CONFLICT (id) DO NOTHING;
COMMIT;

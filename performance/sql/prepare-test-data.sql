BEGIN;

INSERT INTO events (id, title, description, event_date, venue, created_at, updated_at)
VALUES (
    '10000000-0000-0000-0000-000000000001',
    'TicketCraft k6 Performance Event',
    'Synthetic event used only by issue #20 performance tests',
    CURRENT_TIMESTAMP + INTERVAL '30 days',
    'Performance Test Venue',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (id) DO UPDATE
SET title = EXCLUDED.title,
    description = EXCLUDED.description,
    event_date = EXCLUDED.event_date,
    venue = EXCLUDED.venue,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO tickets (
    id, event_id, seat_number, price, status,
    reservation_id, reserved_until, version, created_at, updated_at
)
SELECT
    ('20000000-0000-0000-0000-' || lpad(to_hex(n), 12, '0'))::uuid,
    '10000000-0000-0000-0000-000000000001'::uuid,
    'PERF-' || lpad(n::text, 5, '0'),
    1500.00,
    'AVAILABLE',
    NULL,
    NULL,
    0,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM generate_series(1, 1000) AS n
ON CONFLICT (id) DO NOTHING;

COMMIT;

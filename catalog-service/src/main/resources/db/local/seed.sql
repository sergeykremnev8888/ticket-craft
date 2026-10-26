INSERT INTO events (
    id,
    title,
    description,
    event_date,
    venue,
    created_at,
    updated_at
)
VALUES (
    '11111111-1111-1111-1111-111111111111',
    'TicketCraft Demo Event',
    'Demo event for manual purchase testing',
    CURRENT_TIMESTAMP + INTERVAL '30 days',
    'TicketCraft Arena',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT DO NOTHING;

INSERT INTO tickets (
    id,
    event_id,
    seat_number,
    price,
    status,
    reservation_id,
    reserved_until,
    version,
    created_at,
    updated_at
)
SELECT
    CASE
        WHEN n = 1 THEN
            '22222222-2222-2222-2222-222222222222'::uuid
        ELSE
            (
                '22222222-2222-2222-2222-'
                || LPAD(n::text, 12, '0')
            )::uuid
    END,
    '11111111-1111-1111-1111-111111111111'::uuid,
    'A-' || LPAD(n::text, 2, '0'),
    1500.00,
    'AVAILABLE',
    NULL,
    NULL,
    0,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM generate_series(1, 10) AS seats(n)
ON CONFLICT DO NOTHING;

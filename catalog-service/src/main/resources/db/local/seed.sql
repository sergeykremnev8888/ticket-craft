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
    'TicketCraft E2E Event',
    'Manual E2E test event',
    CURRENT_TIMESTAMP + INTERVAL '30 days',
    'TicketCraft Arena',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (id) DO NOTHING;

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
VALUES (
    '22222222-2222-2222-2222-222222222222',
    '11111111-1111-1111-1111-111111111111',
    'A-01',
    1500.00,
    'AVAILABLE',
    NULL,
    NULL,
    0,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (id) DO NOTHING;

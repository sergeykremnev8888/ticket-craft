BEGIN;

INSERT INTO events (id,title,description,event_date,venue,created_at,updated_at) VALUES
('10000000-0000-0000-0000-000000000004','TicketCraft k6 Spike Event','Issue #20 Stage 4 spike test',CURRENT_TIMESTAMP + INTERVAL '30 days','Performance Test Venue',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('10000000-0000-0000-0000-000000000005','TicketCraft Catalog Chaos Event','Issue #20 Stage 5 catalog outage test',CURRENT_TIMESTAMP + INTERVAL '30 days','Performance Test Venue',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('10000000-0000-0000-0000-000000000006','TicketCraft Payment Chaos Event','Issue #20 Stage 6 payment-service outage test',CURRENT_TIMESTAMP + INTERVAL '30 days','Performance Test Venue',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('10000000-0000-0000-0000-000000000007','TicketCraft Compensation Chaos Event','Issue #20 Stage 7 compensation test',CURRENT_TIMESTAMP + INTERVAL '30 days','Performance Test Venue',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
ON CONFLICT (id) DO UPDATE SET title=EXCLUDED.title,description=EXCLUDED.description,event_date=EXCLUDED.event_date,venue=EXCLUDED.venue,updated_at=CURRENT_TIMESTAMP;

INSERT INTO tickets (id,event_id,seat_number,price,status,reservation_id,reserved_until,version,created_at,updated_at)
SELECT ('50000000-0000-0000-0000-' || lpad(to_hex(n),12,'0'))::uuid,'10000000-0000-0000-0000-000000000004'::uuid,
       'SPIKE-' || lpad(n::text,5,'0'),1500.00,'AVAILABLE',NULL,NULL,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
FROM generate_series(1,3000) n ON CONFLICT (id) DO NOTHING;

INSERT INTO tickets (id,event_id,seat_number,price,status,reservation_id,reserved_until,version,created_at,updated_at)
SELECT ('60000000-0000-0000-0000-' || lpad(to_hex(n),12,'0'))::uuid,'10000000-0000-0000-0000-000000000005'::uuid,
       'CAT-CHAOS-' || lpad(n::text,5,'0'),1500.00,'AVAILABLE',NULL,NULL,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
FROM generate_series(1,2000) n ON CONFLICT (id) DO NOTHING;

INSERT INTO tickets (id,event_id,seat_number,price,status,reservation_id,reserved_until,version,created_at,updated_at)
SELECT ('70000000-0000-0000-0000-' || lpad(to_hex(n),12,'0'))::uuid,'10000000-0000-0000-0000-000000000006'::uuid,
       'PAY-CHAOS-' || lpad(n::text,5,'0'),1500.00,'AVAILABLE',NULL,NULL,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
FROM generate_series(1,2000) n ON CONFLICT (id) DO NOTHING;

INSERT INTO tickets (id,event_id,seat_number,price,status,reservation_id,reserved_until,version,created_at,updated_at)
VALUES ('80000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000007','COMP-00001',1500.00,'AVAILABLE',NULL,NULL,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;
COMMIT;

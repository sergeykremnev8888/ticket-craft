UPDATE tickets SET reserved_until=CURRENT_TIMESTAMP - INTERVAL '1 second',updated_at=CURRENT_TIMESTAMP WHERE id='80000000-0000-0000-0000-000000000001'::uuid AND status='RESERVED';
SELECT id,status,reservation_id,reserved_until FROM tickets WHERE id='80000000-0000-0000-0000-000000000001'::uuid;

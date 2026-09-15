SELECT o.id AS order_id,o.status AS order_status,s.status AS saga_status,s.reservation_id,s.payment_id,o.ticket_id
FROM orders o JOIN order_sagas s ON s.order_id=o.id
WHERE o.created_at >= :'run_started_at'::timestamptz AND o.ticket_id='80000000-0000-0000-0000-000000000001'::uuid;

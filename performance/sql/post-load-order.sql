-- Orders for the synthetic performance event.
SELECT status, COUNT(*) AS orders
FROM orders
WHERE event_id = '10000000-0000-0000-0000-000000000001'
GROUP BY status
ORDER BY status;

SELECT s.status, COUNT(*) AS sagas
FROM order_sagas s
JOIN orders o ON o.id = s.order_id
WHERE o.event_id = '10000000-0000-0000-0000-000000000001'
GROUP BY s.status
ORDER BY s.status;

-- Must be zero after the asynchronous pipeline has drained.
SELECT COUNT(*) AS non_terminal_orders
FROM orders
WHERE event_id = '10000000-0000-0000-0000-000000000001'
  AND status NOT IN ('CONFIRMED', 'CANCELED', 'PAYMENT_FAILED');

SELECT COUNT(*) AS non_terminal_sagas
FROM order_sagas s
JOIN orders o ON o.id = s.order_id
WHERE o.event_id = '10000000-0000-0000-0000-000000000001'
  AND s.status NOT IN ('COMPLETED', 'FAILED');

-- A performance ticket must never create more than one order.
SELECT ticket_id, COUNT(*) AS orders_per_ticket
FROM orders
WHERE event_id = '10000000-0000-0000-0000-000000000001'
GROUP BY ticket_id
HAVING COUNT(*) > 1
ORDER BY orders_per_ticket DESC, ticket_id;

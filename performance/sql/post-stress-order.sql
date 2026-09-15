SELECT status, COUNT(*) AS orders
FROM orders
WHERE ticket_id::text LIKE '30000000-0000-0000-0000-%'
GROUP BY status
ORDER BY status;

SELECT s.status, COUNT(*) AS sagas
FROM order_sagas s
JOIN orders o ON o.id = s.order_id
WHERE o.ticket_id::text LIKE '30000000-0000-0000-0000-%'
GROUP BY s.status
ORDER BY s.status;

SELECT COUNT(*) AS non_terminal_stress_orders
FROM orders
WHERE ticket_id::text LIKE '30000000-0000-0000-0000-%'
  AND status NOT IN ('CONFIRMED', 'CANCELED', 'PAYMENT_FAILED');

SELECT COUNT(*) AS non_terminal_stress_sagas
FROM order_sagas s
JOIN orders o ON o.id = s.order_id
WHERE o.ticket_id::text LIKE '30000000-0000-0000-0000-%'
  AND s.status NOT IN ('COMPLETED', 'FAILED');

SELECT ticket_id, COUNT(*) AS orders_per_ticket
FROM orders
WHERE ticket_id::text LIKE '30000000-0000-0000-0000-%'
GROUP BY ticket_id
HAVING COUNT(*) > 1
ORDER BY orders_per_ticket DESC, ticket_id;

SELECT status, COUNT(*) AS payments
FROM payments
WHERE message_id LIKE 'saga:%:payment-requested'
GROUP BY status
ORDER BY status;

-- For Stage 2 happy-path load this must be zero after the pipeline has drained.
SELECT COUNT(*) AS pending_performance_payments
FROM payments
WHERE message_id LIKE 'saga:%:payment-requested'
  AND status = 'PENDING';

-- Every order must have at most one payment row.
SELECT order_id, COUNT(*) AS payments_per_order
FROM payments
GROUP BY order_id
HAVING COUNT(*) > 1
ORDER BY payments_per_order DESC, order_id;

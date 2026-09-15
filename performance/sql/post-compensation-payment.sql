SELECT status,COUNT(*) AS payments FROM payments WHERE created_at >= :'run_started_at'::timestamptz GROUP BY status ORDER BY status;
SELECT COUNT(*) AS pending_payments FROM payments WHERE created_at >= :'run_started_at'::timestamptz AND status='PENDING';
SELECT order_id,COUNT(*) AS payments_per_order FROM payments WHERE created_at >= :'run_started_at'::timestamptz GROUP BY order_id HAVING COUNT(*)>1 ORDER BY payments_per_order DESC,order_id;

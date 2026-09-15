param(
    [Parameter(Mandatory=$true)][string]$RunStartedAt,
    [Parameter(Mandatory=$true)][ValidateSet('spike','catalog','payment','compensation')][string]$Stage
)
$prefix = switch ($Stage) {
    'spike' { '50000000-0000-0000-0000-' }
    'catalog' { '60000000-0000-0000-0000-' }
    'payment' { '70000000-0000-0000-0000-' }
    'compensation' { '80000000-0000-0000-0000-' }
}
Write-Host "=== Snapshot UTC: $((Get-Date).ToUniversalTime().ToString('o')) ==="
Write-Host "=== Kafka consumer lag ==="
docker exec kafka kafka-consumer-groups --bootstrap-server localhost:9092 --all-groups --describe
Write-Host "`n=== Order outbox non-published ==="
docker exec -i postgres-order psql -U postgres -d order_db -c "SELECT status,event_type,COUNT(*) AS events,MIN(created_at) AS oldest,MAX(attempts) AS max_attempts FROM outbox_events WHERE status <> 'PUBLISHED' GROUP BY status,event_type ORDER BY status,event_type;"
Write-Host "`n=== Catalog outbox non-published ==="
docker exec -i postgres-catalog psql -U postgres -d catalog_db -c "SELECT status,event_type,COUNT(*) AS events,MIN(created_at) AS oldest,MAX(attempts) AS max_attempts FROM outbox_events WHERE status <> 'PUBLISHED' GROUP BY status,event_type ORDER BY status,event_type;"
Write-Host "`n=== Run order/saga state ($Stage) ==="
docker exec -i postgres-order psql -U postgres -d order_db -c "SELECT o.status AS order_status,s.status AS saga_status,COUNT(*) AS rows FROM orders o JOIN order_sagas s ON s.order_id=o.id WHERE o.created_at >= TIMESTAMPTZ '$RunStartedAt' AND o.ticket_id::text LIKE '$prefix%' GROUP BY o.status,s.status ORDER BY o.status,s.status;"

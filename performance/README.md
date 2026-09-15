# TicketCraft performance tests — issue #20

This directory contains reproducible k6 scenarios and SQL verification for issue #20.
Run tests against the local TicketCraft stack only. Performance data is synthetic.

## Prerequisites

- TicketCraft services, Kafka, PostgreSQL, Keycloak and payment-provider-stub are running.
- k6 is installed (`k6 version`).
- A valid access token with `orders.write` and `orders.read` is obtained through the configured Postman OAuth2 Authorization Code + PKCE flow.
- Commands below assume Windows PowerShell from the repository root.

## Prepare test data

```powershell
Get-Content -Raw .\performance\sql\prepare-test-data.sql | docker exec -i postgres-catalog psql -U postgres -d catalog_db
```

The script creates one synthetic event and 1000 independent tickets. Ticket #1 is reserved for smoke. Stage 2 starts from ticket #2.

Verify:

```powershell
docker exec -i postgres-catalog psql -U postgres -d catalog_db -c "SELECT status, COUNT(*) FROM tickets WHERE event_id='10000000-0000-0000-0000-000000000001' GROUP BY status ORDER BY status;"
```

## Stage 1 — smoke

```powershell
$env:ACCESS_TOKEN = '<token-from-postman>'
k6 run .\performance\scripts\smoke.js
```

Expected: 100% checks, 0% HTTP failures, and the smoke order eventually reaches `CONFIRMED / COMPLETED / SOLD`.

## Stage 2 — normal load

The default normal-load profile sends **5 order creations per second for 60 seconds** using a constant-arrival-rate executor. Each iteration receives a unique ticket, starting at ticket #2. At the default settings this schedules approximately 300 independent order sagas.

Run:

```powershell
$env:ACCESS_TOKEN = '<fresh-token-from-postman>'
k6 run .\performance\scripts\load.js
```

Optional overrides:

```powershell
$env:LOAD_RATE = '5'
$env:LOAD_DURATION = '60s'
$env:START_TICKET_NUMBER = '2'
$env:PRE_ALLOCATED_VUS = '20'
$env:MAX_VUS = '50'
k6 run .\performance\scripts\load.js
```

Do not reuse the same `START_TICKET_NUMBER` without cleaning/re-preparing the dataset: successful iterations consume their tickets permanently.

Initial guardrail thresholds are deliberately conservative and are not yet production SLOs:

- checks > 99%
- HTTP request failures < 1%
- create-order p95 < 500 ms
- create-order p99 < 1000 ms

The first successful run establishes a local baseline; later stages will use the measured data to define realistic targets.

## Stage 2 post-load verification

Wait until Kafka/outbox processing has drained, then run:

```powershell
Get-Content -Raw .\performance\sql\post-load-catalog.sql | docker exec -i postgres-catalog psql -U postgres -d catalog_db
Get-Content -Raw .\performance\sql\post-load-order.sql | docker exec -i postgres-order psql -U postgres -d order_db
Get-Content -Raw .\performance\sql\post-load-payment.sql | docker exec -i postgres-payment psql -U postgres -d payment_db
```

For the default happy-path run, expected invariants after the pipeline drains:

- newly used tickets are `SOLD`;
- orders are `CONFIRMED`;
- sagas are `COMPLETED`;
- payments are `SUCCEEDED`;
- `non_terminal_orders = 0`;
- `non_terminal_sagas = 0`;
- `invalid_catalog_rows = 0`;
- no ticket has more than one order;
- no order has more than one payment;
- no unexpected DLT records are produced.

Also inspect Prometheus/Grafana during the run: request latency/rate, JVM CPU/heap/GC, datasource pool, Kafka consumer lag, saga counters and outbox activity. Absolute latency from a local workstation is a baseline, not a production capacity claim.

## Cleanup / reset

Stop load generation before cleanup. The cleanup script removes only the synthetic catalog event/tickets; order/payment rows are intentionally not deleted because those databases own their data. For a fully clean repeated benchmark, recreate/reset the local databases/volumes rather than deleting cross-service history by hand.

```powershell
Get-Content -Raw .\performance\sql\cleanup-test-data.sql | docker exec -i postgres-catalog psql -U postgres -d catalog_db
```

## Stage 3 — Stress / capacity discovery

Stage 3 uses a separate event and 6000 tickets (`30000000-...`) so Stage 1/2 data is not reused.
The stress profile uses linear `ramping-arrival-rate` transitions through targets 10, 20, 40 and 80 orders/s. Each stage lasts 30 seconds; the rate ramps linearly to the next target rather than holding every target for a full 30 seconds.
It is a capacity-discovery run: if the highest level fails a threshold, keep the results; the failure is useful evidence of the current capacity boundary.

PowerShell preparation:

```powershell
Get-Content -Raw .\performance\sql\prepare-stress-data.sql | docker exec -i postgres-catalog psql -U postgres -d catalog_db

docker exec -i postgres-catalog psql -U postgres -d catalog_db -c "SELECT status, COUNT(*) FROM tickets WHERE event_id='10000000-0000-0000-0000-000000000002' GROUP BY status ORDER BY status;"
```

Before the run, obtain a fresh OAuth2 token from Postman, then capture the UTC start time and run k6:

```powershell
$env:ACCESS_TOKEN = '<fresh-access-token-from-postman>'
$env:STRESS_RUN_STARTED_AT = (Get-Date).ToUniversalTime().ToString('o')
k6 run .\performance\scripts\stress.js
```

After k6 finishes, wait until Kafka lag drains to zero before evaluating final DB state:

```powershell
docker exec kafka kafka-consumer-groups --bootstrap-server localhost:9092 --all-groups --describe
```

Post-run verification:

```powershell
Get-Content -Raw .\performance\sql\post-stress-catalog.sql | docker exec -i postgres-catalog psql -U postgres -d catalog_db
Get-Content -Raw .\performance\sql\post-stress-order.sql | docker exec -i postgres-order psql -U postgres -d order_db
Get-Content -Raw .\performance\sql\post-stress-payment.sql | docker exec -i postgres-payment psql -U postgres -d payment_db -v stress_run_started_at="$env:STRESS_RUN_STARTED_AT"
```

Do not run `cleanup-stress-data.sql` until the Stage 3 results have been reviewed.

## Stage 3b — Constant-rate E2E capacity discovery

The ramp stress run proved burst/recovery correctness but did not establish sustainable E2E throughput. `capacity.js` therefore runs a constant arrival rate against an independent 60,000-ticket pool (`40000000-...`). Use non-overlapping 10,000-ticket blocks so runs can be compared without reusing tickets.

Prepare once:

```powershell
Get-Content -Raw .\performance\sql\prepare-capacity-data.sql | docker exec -i postgres-catalog psql -U postgres -d catalog_db
```

Capacity discovery is intentionally stopped once a useful local boundary is known; exact production capacity cannot be inferred from a developer laptop. Before each run set a fresh timestamp and use a non-overlapping ticket range:

```powershell
$env:ACCESS_TOKEN = '<fresh-access-token-from-postman>'
$env:CAPACITY_RATE = '40'
$env:CAPACITY_DURATION = '120s'
$env:START_TICKET_NUMBER = '1'
$env:TICKET_POOL_SIZE = '10000'
$env:CAPACITY_RUN_STARTED_AT = (Get-Date).ToUniversalTime().ToString('o')
k6 run .\performance\scripts\capacity.js
```

While k6 is still running, take snapshots (for example around 30, 60, 90 and 115 seconds) from another PowerShell window:

```powershell
.\performance\scripts\capacity-snapshot.ps1 -RunStartedAt $env:CAPACITY_RUN_STARTED_AT
```

A rate is a sustainable candidate only if backlog does not show a persistent upward trend during steady input. HTTP success alone is insufficient. Compare Kafka lag, non-published outbox depth/oldest event and non-terminal saga counts across snapshots. After k6 stops, wait for convergence and run:

```powershell
Get-Content -Raw .\performance\sql\post-capacity-catalog.sql | docker exec -i postgres-catalog psql -U postgres -d catalog_db
Get-Content -Raw .\performance\sql\post-capacity-order.sql | docker exec -i postgres-order psql -U postgres -d order_db -v capacity_run_started_at="$env:CAPACITY_RUN_STARTED_AT"
Get-Content -Raw .\performance\sql\post-capacity-payment.sql | docker exec -i postgres-payment psql -U postgres -d payment_db -v capacity_run_started_at="$env:CAPACITY_RUN_STARTED_AT"
```

`cleanup-capacity-data.sql` removes only catalog-owned synthetic data and must not be run while a saga is still in flight. Local measurements recorded for issue #20 established 10 orders/s as sustainable E2E on the test laptop; 20 and 40 orders/s kept HTTP ingress healthy but accumulated asynchronous backlog before eventual recovery. Treat these as environment-specific observations, not production SLOs.


## Stage 4 — Spike and recovery

Stage 4 uses event `...0004` and ticket prefix `50000000-...`. The profile is deliberately short for a developer workstation: 5/s for 15 seconds, a near-instant jump to 30/s, 30/s for 15 seconds, a near-instant drop back to 5/s, then 5/s for 30 seconds. The goal is recovery behavior, not a new capacity number.

Prepare Stage 4–7 synthetic data once:

```powershell
Get-Content -Raw .\performance\sql\prepare-resilience-data.sql | docker exec -i postgres-catalog psql -U postgres -d catalog_db
```

Run the spike with a fresh Postman PKCE access token:

```powershell
$env:ACCESS_TOKEN = '<fresh-access-token-from-postman>'
$env:RESILIENCE_RUN_STARTED_AT = (Get-Date).ToUniversalTime().ToString('o')
k6 run .\performance\scripts\spike.js
```

Take snapshots during the 30/s plateau and after the return to 5/s:

```powershell
.\performance\scripts\resilience-snapshot.ps1 -Stage spike -RunStartedAt $env:RESILIENCE_RUN_STARTED_AT
```

After the pipeline drains, verify all three databases:

```powershell
Get-Content -Raw .\performance\sql\post-spike-catalog.sql | docker exec -i postgres-catalog psql -U postgres -d catalog_db
Get-Content -Raw .\performance\sql\post-spike-order.sql | docker exec -i postgres-order psql -U postgres -d order_db -v run_started_at="$env:RESILIENCE_RUN_STARTED_AT"
Get-Content -Raw .\performance\sql\post-spike-payment.sql | docker exec -i postgres-payment psql -U postgres -d payment_db -v run_started_at="$env:RESILIENCE_RUN_STARTED_AT"
```

PASS means HTTP/business failures stay within the conservative thresholds, no duplicate ticket orders/payments appear, all created sagas eventually reach a terminal correct state, critical Kafka lag returns to zero, and both outboxes drain. A temporary backlog during the spike is expected.

## Stage 5 — Catalog-service outage and recovery

Stage 5 uses event `...0005` and ticket prefix `60000000-...`. It verifies that order creation remains durable while the catalog Kafka consumer is unavailable and that queued reservation work is processed after catalog-service returns. Do not kill PostgreSQL or Kafka for this scenario; stop only the catalog-service application process (for example, Stop in Eclipse).

Start a 60-second, 5 orders/s run:

```powershell
$env:ACCESS_TOKEN = '<fresh-access-token-from-postman>'
$env:CHAOS_RATE = '5'
$env:CHAOS_DURATION = '60s'
$env:RESILIENCE_RUN_STARTED_AT = (Get-Date).ToUniversalTime().ToString('o')
k6 run .\performance\scripts\chaos-catalog.js
```

During the run, allow approximately 15 seconds of healthy traffic, stop catalog-service for approximately 15 seconds, take a snapshot, then start catalog-service again. Take another snapshot while it recovers and a final one after k6 has stopped:

```powershell
.\performance\scripts\resilience-snapshot.ps1 -Stage catalog -RunStartedAt $env:RESILIENCE_RUN_STARTED_AT
```

Expected during the outage: `ticket-reservation-commands` lag and/or upstream saga/outbox backlog grows; orders must not be falsely marked `CONFIRMED`. Expected after restart: the backlog drains and successful happy-path orders converge to `CONFIRMED / COMPLETED / SOLD` without duplicates.

Final verification:

```powershell
Get-Content -Raw .\performance\sql\post-catalog-chaos-catalog.sql | docker exec -i postgres-catalog psql -U postgres -d catalog_db
Get-Content -Raw .\performance\sql\post-catalog-chaos-order.sql | docker exec -i postgres-order psql -U postgres -d order_db -v run_started_at="$env:RESILIENCE_RUN_STARTED_AT"
Get-Content -Raw .\performance\sql\post-catalog-chaos-payment.sql | docker exec -i postgres-payment psql -U postgres -d payment_db -v run_started_at="$env:RESILIENCE_RUN_STARTED_AT"
```

## Stage 6 — Payment-service outage and recovery

Stage 6 uses event `...0006` and ticket prefix `70000000-...`. Stop **payment-service itself**, not the external provider stub. This distinction is intentional: payment requests remain durable in Kafka while the consumer is down and can be processed automatically after restart. A long provider outage can exhaust the configured retry budget and legitimately route records to a DLT, which is a different recovery exercise.

Run 5 orders/s for 60 seconds:

```powershell
$env:ACCESS_TOKEN = '<fresh-access-token-from-postman>'
$env:CHAOS_RATE = '5'
$env:CHAOS_DURATION = '60s'
$env:RESILIENCE_RUN_STARTED_AT = (Get-Date).ToUniversalTime().ToString('o')
k6 run .\performance\scripts\chaos-payment.js
```

After approximately 15 healthy seconds, stop payment-service for approximately 15 seconds. During the outage capture a snapshot; then restart payment-service and capture recovery snapshots:

```powershell
.\performance\scripts\resilience-snapshot.ps1 -Stage payment -RunStartedAt $env:RESILIENCE_RUN_STARTED_AT
```

Expected during the outage: `payment-requests` lag grows and sagas wait for payment; no order may become `CONFIRMED` without a successful payment. After restart the payment consumer should catch up and the normal saga should converge without duplicate payments.

Final verification:

```powershell
Get-Content -Raw .\performance\sql\post-payment-chaos-catalog.sql | docker exec -i postgres-catalog psql -U postgres -d catalog_db
Get-Content -Raw .\performance\sql\post-payment-chaos-order.sql | docker exec -i postgres-order psql -U postgres -d order_db -v run_started_at="$env:RESILIENCE_RUN_STARTED_AT"
Get-Content -Raw .\performance\sql\post-payment-chaos-payment.sql | docker exec -i postgres-payment psql -U postgres -d payment_db -v run_started_at="$env:RESILIENCE_RUN_STARTED_AT"
```

## Stage 7 — Deterministic compensation under failure

Stage 7 uses one dedicated ticket, `80000000-0000-0000-0000-000000000001`. It deliberately creates the failure window without racing a human against the saga: payment-service is stopped first so the ticket can be reserved while payment cannot complete; catalog-service is then stopped, the reservation is expired in catalog DB, payment-service is allowed to complete the queued payment, and finally catalog-service is restarted to consume the queued confirmation. The expected confirmation failure is `RESERVATION_EXPIRED`, followed by refund compensation.

Use a clean Stage 7 ticket. Before starting, verify it is `AVAILABLE`; if this scenario was already executed, recreate/reset the local test databases rather than rewriting historical order/payment ownership by hand.

First stop **payment-service only**, keep catalog/order/Kafka/PostgreSQL/provider running, obtain a fresh token, and create exactly one order:

```powershell
$env:ACCESS_TOKEN = '<fresh-access-token-from-postman>'
$env:RESILIENCE_RUN_STARTED_AT = (Get-Date).ToUniversalTime().ToString('o')
k6 run .\performance\scripts\chaos-compensation.js
```

Wait until the ticket is `RESERVED` and the saga is waiting for payment. Confirm with:

```powershell
docker exec -i postgres-catalog psql -U postgres -d catalog_db -c "SELECT id,status,reservation_id,reserved_until FROM tickets WHERE id='80000000-0000-0000-0000-000000000001';"
.\performance\scripts\resilience-snapshot.ps1 -Stage compensation -RunStartedAt $env:RESILIENCE_RUN_STARTED_AT
```

Now stop **catalog-service as well**. Expire only this synthetic reservation:

```powershell
Get-Content -Raw .\performance\sql\expire-compensation-ticket.sql | docker exec -i postgres-catalog psql -U postgres -d catalog_db
```

Start payment-service while catalog-service remains stopped. Wait until the payment becomes `SUCCEEDED` and the order has emitted/queued `ConfirmTicket`. Then start catalog-service. It must reject confirmation as expired; order-service must enter payment compensation; payment-service must refund the same payment idempotently.

After the system drains, run:

```powershell
.\performance\scripts\resilience-snapshot.ps1 -Stage compensation -RunStartedAt $env:RESILIENCE_RUN_STARTED_AT
Get-Content -Raw .\performance\sql\post-compensation-catalog.sql | docker exec -i postgres-catalog psql -U postgres -d catalog_db
Get-Content -Raw .\performance\sql\post-compensation-order.sql | docker exec -i postgres-order psql -U postgres -d order_db -v run_started_at="$env:RESILIENCE_RUN_STARTED_AT"
Get-Content -Raw .\performance\sql\post-compensation-payment.sql | docker exec -i postgres-payment psql -U postgres -d payment_db -v run_started_at="$env:RESILIENCE_RUN_STARTED_AT"
```

Expected final invariant: ticket `AVAILABLE` with cleared reservation fields, order `CANCELED`, saga `FAILED`, payment `REFUNDED`, no `OrderConfirmed` for this order, no duplicate payment, critical Kafka lag zero and both outboxes drained. Check the relevant DLT topics as well; this deterministic business failure should be compensated rather than ending in a DLT.

## Stage 4–7 cleanup

Only after all sagas are terminal and Kafka/outboxes are drained:

```powershell
Get-Content -Raw .\performance\sql\cleanup-resilience-data.sql | docker exec -i postgres-catalog psql -U postgres -d catalog_db
```

This cleanup removes only catalog-owned synthetic events/tickets. It intentionally does not delete order/payment history owned by other services.

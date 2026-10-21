# TicketCraft — API Gateway

API Gateway — единая точка входа для HTTP API TicketCraft. Сервис отвечает за маршрутизацию, проверку JWT, ограничение частоты запросов, защиту от сбоев downstream-сервисов, CORS и базовую наблюдаемость. Бизнес-логика остаётся в соответствующих сервисах.

> **Статус:** локальный MVP. Функциональность ниже зафиксирована по результатам ручных проверок и тестов в ходе разработки. Этот README не является утверждением о production readiness. Точные параметры лимитов, таймаутов и Circuit Breaker следует сверять с текущим `application.yml`.

## Технологии

- Java 21, Spring Boot 4.1.1, Spring Cloud Gateway (WebFlux), Spring Security OAuth2 Resource Server.
- Keycloak — выпуск JWT; Redis — распределённый rate limiting.
- Resilience4j — Circuit Breaker; Spring Boot Actuator + Micrometer — метрики.
- Prometheus и Grafana — сбор и визуализация метрик.

Локальный адрес Gateway: `http://localhost:8080`.

## HTTP API и маршруты

| Метод | Путь | Доступ | Назначение |
|---|---|---|---|
| GET | `/api/v1/catalog/events` и `/api/v1/catalog/events/**` | Public | Просмотр событий через Catalog Service |
| POST | `/api/v1/orders` | Public (текущее правило Gateway) | Создание заказа через Order Service |
| GET | `/api/v1/orders/**` | JWT с `SCOPE_orders.read` | Получение информации о заказе |
| GET | `/debug/slow` | Public, диагностический | Тестирование таймаутов/CB; **убрать из публичного доступа** |
| `/fallback/**` | Внутренний fallback-маршрут | Разрешён текущей конфигурацией | Ответ при недоступности downstream; **закрыть от внешнего вызова** |
| GET | `/actuator/health` | Public | Health check |
| GET | `/actuator/prometheus` | Public для локальной разработки | Экспорт метрик; **ограничить доступ вне dev** |
| GET | `/actuator/metrics` и `/actuator/metrics/**` | Public при текущей диагностической настройке | Просмотр метрик Micrometer; **закрыть вне dev** |

> Таблица отражает правила авторизации Gateway. Детальные HTTP-коды и JSON-схемы бизнес-ответов документируются в API соответствующих сервисов.

## Безопасность

- JWT валидируется Spring Security Resource Server с использованием настроенного Keycloak issuer/JWK.
- Для чтения заказов требуется authority `SCOPE_orders.read`.
- Для остальных не перечисленных маршрутов действует `denyAll`.
- CSRF отключён для stateless API.
- **Важное ограничение:** создание заказа сейчас разрешено без JWT на уровне Gateway, однако работающий Order Service отвечает `401 Unauthorized` без Bearer-токена. Правила авторизации Gateway и downstream **не согласованы**; перед полноценным тестом создания заказа требуется JWT и корректный запрос.

## CORS

Разрешённый Origin: `http://localhost:3000`.

- Методы: `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `OPTIONS`.
- Разрешённые заголовки: `Authorization`, `Content-Type`, `X-Correlation-ID`.
- Доступный frontend заголовок ответа: `X-Correlation-ID`.
- Preflight cache (`maxAge`): 3600 секунд.
- Конфигурация CORS находится в Spring Security.

Проверено: разрешённый preflight → 200; неизвестный Origin → 403; обычный GET получает CORS-заголовки.

## Resilience

### Rate limiting

Redis-backed `RequestRateLimiter` с идентификацией клиента по IP-адресу (`ipKeyResolver`):

| Маршрут | `replenishRate` | `burstCapacity` | `requestedTokens` |
|---|---:|---:|---:|
| Catalog | 10 токенов/с | 20 | 1 |
| Order | 5 токенов/с | 10 | 1 |

При превышении лимита возвращается HTTP `429 Too Many Requests`.

Используется обёртка `ResilientRateLimiter` над `RedisRateLimiter`: ожидание Redis ограничено `500ms`; при ошибке/таймауте Redis действует **fail-open** — запрос пропускается с предупреждением в логе. Это сознательный компромисс в пользу доступности; при длительном отказе Redis защита от перегрузки ослабевает. Нужны отдельные метрики/алерты на fail-open.

### Timeouts и Circuit Breaker

HTTP-клиент Gateway:

- `connect-timeout`: **2000 мс**.
- `response-timeout`: **5 секунд**.

Resilience4j Circuit Breaker (CB) настроен для двух маршрутов:

| Маршрут | Имя CB | Fallback | HTTP |
|---|---|---|---|
| Catalog | `catalogCircuitBreaker` | `forward:/fallback/catalog` | 503, `CATALOG_UNAVAILABLE` |
| Order | `orderCircuitBreaker` | `forward:/fallback/order` | 503, `ORDER_SERVICE_UNAVAILABLE` |

Параметры обоих CB:

| Параметр | Значение |
|---|---|
| `slidingWindowType` | `COUNT_BASED` |
| `slidingWindowSize` | `10` |
| `minimumNumberOfCalls` | `10` |
| `failureRateThreshold` | `50%` |
| `waitDurationInOpenState` | `10s` |
| `permittedNumberOfCallsInHalfOpenState` | `3` |
| `slowCallDurationThreshold` | `2s` |
| `slowCallRateThreshold` | `50%` |
| `TimeLimiter.timeoutDuration` | `4s` |
| `statusCodes` | `503` |

Fallback для Order принимает в том числе `POST` (при `forward:` исходный HTTP-метод сохраняется). Тело ответа при недоступности:

```json
{
  "code": "ORDER_SERVICE_UNAVAILABLE",
  "message": "Order service is temporarily unavailable. The result of a previous request may be unknown."
}
```

**Важный риск для POST:** если downstream обработал запрос, но ответ потерялся, клиент не знает, был ли создан заказ. Поэтому автоматический retry на создание заказа **не включён**. Нужна идемпотентность по ключу запроса (`Idempotency-Key`) на уровне Order Service.

### Correlation ID

Gateway принимает входящий `X-Correlation-ID` либо генерирует его, если идентификатор отсутствует/пуст. ID передаётся downstream и возвращается в ответе. В логах Gateway фиксируются метод, путь, статус и длительность запроса.

## Observability

- `GET /actuator/health` — проверено: HTTP 200, `status=UP`.
- `GET /actuator/prometheus` — проверена выдача JVM/HTTP/Resilience4j-метрик.
- Prometheus опрашивает `host.docker.internal:8080/actuator/prometheus` с job `api-gateway` (локальный запуск Gateway на Windows, Prometheus в Docker).
- Grafana использует provisioned Prometheus datasource с UID `prometheus`.
- Дашборд: `observability/grafana/dashboards/api-gateway.json`; автоматически подхватывается Grafana provisioning.
- Проверено в Grafana: `up{job="api-gateway"} = 1`; доступны метрики `catalogCircuitBreaker` и `orderCircuitBreaker`.
- На `/actuator/prometheus` проверены метрики `resilience4j_circuitbreaker_state`, `resilience4j_circuitbreaker_calls_seconds_count` и `resilience4j_circuitbreaker_not_permitted_calls_total` для Order.
- `/actuator/metrics` был временно разрешён в `GatewaySecurityConfig` для диагностики; для его HTTP-публикации требуется также включить `metrics` в `management.endpoints.web.exposure.include`. Вне dev доступ необходимо закрыть.

`UP` у Gateway **не означает**, что все downstream-сервисы и Redis доступны.

## Локальная проверка

Предполагается, что инфраструктура запущена через Docker Compose, а Gateway — из IDE или Maven.

```powershell
curl.exe -i http://localhost:8080/actuator/health
curl.exe -s http://localhost:8080/actuator/prometheus

# CORS preflight
curl.exe -i -X OPTIONS http://localhost:8080/api/v1/catalog/events `
  -H "Origin: http://localhost:3000" `
  -H "Access-Control-Request-Method: GET"

# Public catalog route
curl.exe -i http://localhost:8080/api/v1/catalog/events

# Проверка Order при остановленном downstream (ожидается fallback 503)
curl.exe -i -X POST http://localhost:8080/api/v1/orders `
  -H "Content-Type: application/json" `
  -d "{}"

# Метрики состояния Order CB
curl.exe -s http://localhost:8080/actuator/prometheus |
  Select-String 'resilience4j_circuitbreaker_(state|calls_seconds_count|not_permitted_calls_total).*orderCircuitBreaker'
```

Мониторинг: Prometheus `http://localhost:9090/targets`, Grafana `http://localhost:3000`.

Запуск тестов из корня multi-module Maven проекта (при корректно настроенных зависимостях):

```bash
mvn -pl api-gateway -am test
```

## TODO

### Приоритет 1

- [ ] Убрать или защитить `/debug/slow` и прямой доступ к `/fallback/**`.
- [ ] Закрыть `/actuator/prometheus` и временно открытый `/actuator/metrics/**` от публичного интернета (внутренняя сеть, отдельный management port или auth); проверить exposure других actuator endpoints.
- [ ] Согласовать правила доступа для `POST /api/v1/orders`: Gateway разрешает анонимный POST, но Order Service требует JWT (`401` без токена); определить владельца заказа и downstream-проверки.
- [ ] Проверить модель доверия к `X-Correlation-ID`: валидация длины/символов, предотвращение log injection, единообразное поведение при нескольких значениях.
- [ ] Протестировать политику Redis fail-open при реальном отказе/таймауте и поведение при недоступности Keycloak/JWK endpoint; добавить мониторинг таких случаев.
- [x] Зафиксировать текущие значения rate limits, timeout и CB для Catalog и Order; сверять при изменениях `application.yml`.

### Приоритет 2

- [ ] Автоматизировать интеграционные тесты: 401/403, 429, timeout, fallback, CB transitions, propagation correlation ID (часть сценариев уже проверена вручную).
- [ ] Проверить retry policy (только для безопасных/идемпотентных операций, с backoff/jitter и ограничением бюджета попыток); не ретраить создание заказа без гарантий идемпотентности.
- [ ] Добавить HTTP latency histogram для корректных p95/p99 и панели/алерты по SLO.
- [ ] Настроить алерты: недоступность Gateway, рост 5xx, latency, CB OPEN, ошибки Redis.
- [ ] Добавить метрики rate limiter и Redis fail-open; проверить отсутствие дублирования `X-RateLimit-*` заголовков.
- [ ] Настроить structured JSON logs, сквозную трассировку OpenTelemetry → Tempo и связку traceId/correlationId.
- [ ] Провести нагрузочные тесты и проверить поведение при отказах Catalog/Order/Redis.
- [ ] Уточнить Kubernetes/Docker deployment, readiness/liveness probes и управление секретами.

### После MVP

- [ ] Опубликовать контракт API (OpenAPI) и примеры типовых ошибок Gateway.
- [ ] Добавить автоматические smoke-тесты в CI и поддерживать README синхронно с конфигурацией.

## Границы ответственности

Gateway отвечает за edge concerns (routing, authentication, throttling, resilience, observability). Авторизация доступа к конкретному заказу, идемпотентность создания заказа, резервирование билета, оплата и Saga выполняются в бизнес-сервисах; Gateway не должен подменять их проверки.

# TicketCraft — API Gateway

API Gateway — единая точка входа для HTTP API TicketCraft. Сервис отвечает за маршрутизацию, проверку JWT, ограничение частоты запросов, защиту от сбоев downstream-сервисов, CORS и базовую наблюдаемость. Бизнес-логика остаётся в соответствующих сервисах.

> **Статус:** локальный MVP. Функциональность ниже зафиксирована по результатам ручных проверок и тестов в ходе разработки. Этот README не является утверждением о production readiness. Точные параметры лимитов, таймаутов и Circuit Breaker следует сверять с текущим `application.yml`.

## Технологии

- Java 21, Spring Boot 4.1.1, Spring Cloud Gateway (WebFlux), Spring Security OAuth2 Resource Server.
- Keycloak — выпуск JWT; Redis — распределённый rate limiting.
- Resilience4j — Circuit Breaker; Spring Boot Actuator + Micrometer — метрики.
- Prometheus и Grafana — сбор и визуализация метрик.
- Micrometer Tracing / OpenTelemetry → Grafana Tempo — распределённая трассировка (distributed tracing).

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
| GET | `/actuator/metrics` и `/actuator/metrics/**` | **Не разрешён** текущим `GatewaySecurityConfig` | Endpoint включён в exposure, но доступ блокируется политикой `denyAll` |

> Таблица отражает правила авторизации Gateway. Детальные HTTP-коды и JSON-схемы бизнес-ответов документируются в API соответствующих сервисов.

## Безопасность

- JWT валидируется Spring Security Resource Server с использованием настроенного Keycloak issuer/JWK.
- Для чтения заказов требуется authority `SCOPE_orders.read`.
- Для остальных не перечисленных маршрутов действует `denyAll`.
- CSRF отключён для stateless API.
- **Важное ограничение:** создание заказа разрешено без JWT на уровне Gateway, но **Order Service требует Bearer JWT**. Реальный контракт `POST /api/v1/orders`: заголовок `Idempotency-Key`, тело `{"ticketId":"<UUID>"}`; `userId` берётся из JWT. Анонимный вызов без токена возвращает `401` из Order Service. Правила на edge следует согласовать с downstream.

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

**Важный риск для POST:** если downstream обработал запрос, но ответ потерялся, клиент не знает, был ли создан заказ. Поэтому автоматический retry на создание заказа **не включён**. **Идемпотентность уже реализована в Order Service**: контроллер требует `Idempotency-Key`, а `OrderService.createOrder()` вызывает `IdempotencyService` с хешем пары `(userId, ticketId)`. Повторный запрос с завершённым ключом возвращает существующий заказ. Следует отдельно проверить сценарии конкурентных запросов, ошибки и повтор после таймаута.

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
- `metrics` присутствует в `management.endpoints.web.exposure.include`, но текущий `GatewaySecurityConfig` **не разрешает** `/actuator/metrics/**`; анонимный доступ блокируется. `prometheus` разрешён для локального окружения.

### Distributed Tracing: OpenTelemetry → Tempo

В `api-gateway/pom.xml` подключён `spring-boot-starter-opentelemetry`. Конфигурация в `application.yml`:

- `spring.reactor.context-propagation: auto` — передача контекста в Reactor, необходимая для корректного отображения Trace ID/Span ID в реактивных логах.
- `management.tracing.sampling.probability: 1.0` — 100% запросов в локальной разработке; для production значение следует снизить.
- `management.opentelemetry.tracing.export.otlp.endpoint: http://localhost:4318/v1/traces` — OTLP HTTP экспорт трассировок в Tempo (порт 4318 опубликован в `docker-compose.yml`, приёмник задан в `observability/tempo/tempo.yml`).
- `management.otlp.metrics.export.enabled: false` — OTLP **метрики** не отправляются в Tempo; для метрик остаётся Prometheus. Это устраняет `404` на `/v1/metrics`.

**Проверено вручную 21.10.2026:** логи Gateway содержат Trace ID и Span ID; в Grafana → Explore → Tempo найдена трассировка `GET /api/v1/catalog/events` по Trace ID `d2c0478768f4cad70492744c55bfc859`. Трассировка включала **14 spans**, в том числе `api-gateway`, `catalog-service`, операции Spring Security и Redis. Это подтверждает сквозную передачу trace-контекста **Gateway → Catalog** и успешный экспорт в Tempo. Общая длительность зафиксированного запроса — около 490 мс. Время отдельных вложенных spans нельзя суммировать.

`X-Correlation-ID` — отдельный прикладной идентификатор запроса (UUID/переданное значение); **он не равен** OpenTelemetry `traceId`. Trace ID — 32 hex-символа, Span ID — 16 hex-символов. Сейчас оба механизма существуют параллельно. Связь между ними в structured logs ещё предстоит сделать.

Для поиска: Grafana `http://localhost:3000` → **Explore** → datasource **Tempo** → поиск по Trace ID. Retention Tempo в локальной конфигурации: `1h`; старые traces могут исчезать.

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

# После включения tracing — ищи Trace ID в логах Gateway,
# затем открой Grafana → Explore → Tempo → Trace ID.

Мониторинг: Prometheus `http://localhost:9090/targets`, Grafana `http://localhost:3000`.

Запуск тестов из корня multi-module Maven проекта (при корректно настроенных зависимостях):

```bash
mvn -pl api-gateway -am test
```

## TODO

### Приоритет 1

- [ ] Убрать или защитить `/debug/slow` и прямой доступ к `/fallback/**`.
- [ ] Закрыть `/actuator/prometheus` от публичного интернета (внутренняя сеть, отдельный management port или auth); пересмотреть необходимость публикации `/actuator/metrics` (сейчас блокируется Security).
- [ ] Согласовать правила доступа для `POST /api/v1/orders`: Gateway разрешает анонимный POST, но Order Service требует JWT (`401` без токена); на Gateway логично требовать аутентификацию и отдельно проверить scope/roles. Владелец заказа уже определяется в Order Service по JWT.
- [ ] Проверить модель доверия к `X-Correlation-ID`: валидация длины/символов, предотвращение log injection, единообразное поведение при нескольких значениях.
- [ ] Протестировать политику Redis fail-open при реальном отказе/таймауте и поведение при недоступности Keycloak/JWK endpoint; добавить мониторинг таких случаев.
- [x] Зафиксировать текущие значения rate limits, timeout и CB для Catalog и Order; сверять при изменениях `application.yml`.

### Приоритет 2

- [ ] Автоматизировать интеграционные тесты: 401/403, 429, timeout, fallback, CB transitions, propagation correlation ID (часть сценариев уже проверена вручную).
- [ ] Проверить retry policy (только для безопасных/идемпотентных операций, с backoff/jitter и ограничением бюджета попыток); для POST не включать автоматический retry без отдельной проверки гарантий уже реализованного `Idempotency-Key`.
- [ ] Добавить HTTP latency histogram для корректных p95/p99 и панели/алерты по SLO.
- [ ] Настроить алерты: недоступность Gateway, рост 5xx, latency, CB OPEN, ошибки Redis.
- [ ] Добавить метрики rate limiter и Redis fail-open; проверить отсутствие дублирования `X-RateLimit-*` заголовков.
- [x] Подключить OpenTelemetry → Tempo; проверить сквозную трассировку Gateway → Catalog в Grafana.
- [ ] Настроить structured JSON logs и удобную связь `traceId` / `spanId` / `correlationId` в логах; проверить tracing для Gateway → Order.
- [ ] Провести нагрузочные тесты и проверить поведение при отказах Catalog/Order/Redis.
- [ ] Уточнить Kubernetes/Docker deployment, readiness/liveness probes и управление секретами.

### После MVP

- [ ] Опубликовать контракт API (OpenAPI) и примеры типовых ошибок Gateway.
- [ ] Добавить автоматические smoke-тесты в CI и поддерживать README синхронно с конфигурацией.

## Известные ограничения и технические замечания

- В `ResilientRateLimiter.isAllowed()` остался диагностический `log.info("RateLimiter route={}, config={}", ...)` на каждый запрос. После проверки конфигурации удалить либо перевести в DEBUG.
- `ipKeyResolver` берёт `remoteAddress`, а не проверенный клиентский IP из доверенного proxy. При запуске за reverse proxy/Kubernetes Ingress это может приводить к общему лимиту на IP прокси. Нужна отдельная настройка доверенных forwarded headers.
- `/fallback/**` доступен извне из-за `permitAll`; желательно закрыть прямой вызов fallback, сохранив внутренний `forward:`. `/debug/slow` сейчас публичен.
- В текущей YAML-конфигурации на обоих маршрутах фильтр `CircuitBreaker` объявлен **перед** `RequestRateLimiter`. Проверить порядок выполнения фильтров и желаемую семантику: rate limit обычно выгоднее применять до обращения к downstream/CB.
- Верифицированное восстановление Order CB: `CLOSED → OPEN → CLOSED`; `HALF_OPEN` как отдельное состояние в метриках непосредственно не зафиксировано.

## Границы ответственности

Gateway отвечает за edge concerns (routing, authentication, throttling, resilience, observability). Авторизация доступа к конкретному заказу, идемпотентность создания заказа, резервирование билета, оплата и Saga выполняются в бизнес-сервисах; Gateway не должен подменять их проверки.

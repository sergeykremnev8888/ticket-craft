# TicketCraft — API Gateway

API Gateway — единая точка входа для HTTP API учебного проекта TicketCraft. Шлюз маршрутизирует запросы, проверяет JWT, ограничивает частоту обращений и защищает HTTP-вызовы сервисов через Circuit Breaker и таймауты. Бизнес-авторизация и бизнес-логика остаются в downstream-сервисах.

## Стек

- Java 21, Spring Boot 4.1.1, Spring Cloud 2025.1.3.
- Spring Cloud Gateway Server WebFlux, Reactor Netty.
- Spring Security OAuth2 Resource Server; Keycloak выпускает JWT.
- Redis и реактивный Redis-клиент Lettuce для распределённого rate limiting.
- Resilience4j: Circuit Breaker и TimeLimiter.
- Actuator, Micrometer, Prometheus, Grafana.
- Micrometer Tracing / OpenTelemetry, экспорт OTLP в Tempo.
- JUnit, Mockito, Reactor Test, WebTestClient, Testcontainers 2.0.5.

## Маршруты и доступ

Адрес Gateway по умолчанию: `http://localhost:8080`.

| Метод | Путь | Правило Gateway | Назначение |
|---|---|---|---|
| GET | `/api/v1/catalog/events`, `/api/v1/catalog/events/**` | Публичный | Каталог мероприятий |
| POST | `/api/v1/orders` | Аутентифицированный пользователь | Создание заказа |
| GET | `/api/v1/orders/**` | Аутентифицированный пользователь | Чтение заказа |
| GET | `/debug/slow` | Публичный, только профиль `local` | Диагностика таймаутов и CB |
| GET | `/actuator/health` | Публичный | Состояние приложения |
| GET | `/actuator/prometheus` | Публичный в текущей конфигурации | Экспорт метрик |
| GET | `/fallback/catalog` | Публичный в текущей конфигурации | Fallback каталога |
| Без ограничения метода в контроллере | `/fallback/order` | Публичный в текущей конфигурации | Fallback заказов, включая POST |

Для остальных запросов действует `denyAll`. Отказ Security зависит от наличия аутентификации: обычно `401` для анонимного запроса и `403` для аутентифицированного.

Основные маршруты заданы в [application.yml](src/main/resources/application.yml). Диагностический маршрут регистрируется отдельно через [LocalDebugRoutesConfig](src/main/java/ru/ticketcraft/gateway/config/LocalDebugRoutesConfig.java), поэтому не переопределяет список основных маршрутов.

## Границы ответственности и безопасность

Gateway проверяет JWT: подпись, срок действия, issuer и audience. По умолчанию:

- issuer: `http://localhost:9000/realms/ticketcraft`;
- audience: `ticketcraft-api`.

Gateway требует аутентификацию для API заказов, но не дублирует проверки `orders.read` и `orders.write`. Проверка scopes, владельца заказа и допустимости бизнес-операции выполняется в Order Service. Каждый сервис самостоятельно защищает свою границу и проверяет JWT.

Для stateless Bearer API в Gateway отключён CSRF. Браузерный доступ настраивается через CORS. Публичный каталог не требует токена; переданный невалидный Bearer-токен может быть отклонён Resource Server даже на публичном пути.

Gateway не реализует идемпотентность создания заказа. Он передаёт `Idempotency-Key` в Order Service, который отвечает за обработку повторов. Автоматический retry POST на Gateway не включён.

## CORS

Параметры привязываются к [GatewayCorsProperties](src/main/java/ru/ticketcraft/gateway/security/GatewayCorsProperties.java) и применяются через Spring Security.

```yaml
ticketcraft:
  gateway:
    cors:
      allowed-origins:
        - http://localhost:3000
      allowed-methods:
        - GET
        - POST
      allowed-headers:
        - Authorization
        - Content-Type
        - X-Correlation-ID
        - Idempotency-Key
      exposed-headers:
        - X-Correlation-ID
        - Location
      max-age: 1h
```

`allowed-headers` определяет заголовки, разрешённые в браузерном запросе; `exposed-headers` — заголовки ответа, доступные JavaScript. Preflight OPTIONS обрабатывается CORS-механизмом и не требует отдельного разрешения OPTIONS как бизнес-метода API.

## Rate limiting

На обоих основных маршрутах порядок фильтров:

1. `ProblemRequestRateLimiter`;
2. `CircuitBreaker`;
3. HTTP-вызов downstream.

Если лимит исчерпан, возвращается локальный `429`: downstream не вызывается и CB не учитывает этот запрос. Ожидание Redis происходит до входа в CB.

| Маршрут | Resolver и ключ | Пополнение | Ёмкость корзины | Токенов на запрос |
|---|---|---:|---:|---:|
| Catalog | `ipKeyResolver`: IP прямого подключения | 10/с | 20 | 1 |
| Order | `userKeyResolver`: `user:<JWT sub>` | 5/с | 10 | 1 |

Ключ пользователя берётся из проверенного principal Spring Security. При отсутствии или пустом `sub` resolver возвращает пустой результат, и фильтр отклоняет запрос с `403 RATE_LIMIT_KEY_MISSING`. Resolver по умолчанию — пользовательский; маршруты явно выбирают нужный resolver.

Состояние token bucket хранится в Redis и разделяется по маршруту и ключу клиента. Несколько Gateway с одинаковой конфигурацией могут использовать общий Redis; отдельный интеграционный тест двух экземпляров пока не добавлен.

`ProblemRequestRateLimiter` оборачивает стандартный фильтр Gateway, сохраняет заголовки `X-RateLimit-*` и формирует ProblemDetail только для локальных отказов. Ответы `403/429` от downstream не преобразуются этой обёрткой.

### Отказ Redis

[ResilientRateLimiter](src/main/java/ru/ticketcraft/gateway/ratelimiter/ResilientRateLimiter.java) реализует fail-open: при ошибке или превышении времени ожидания запрос разрешается. Это сохраняет доступность API, но ослабляет защиту от перегрузки.

```yaml
ticketcraft:
  gateway:
    rate-limiter:
      timeout: 500ms
      warn-interval: 30s
```

Параметры читаются через `GatewayRateLimiterProperties`, оба должны быть положительными.

Счётчик Micrometer: `ticketcraft.gateway.rate.limiter.fail.open`. В Prometheus: `ticketcraft_gateway_rate_limiter_fail_open_total`.

Теги:

- `route` — ID маршрута;
- `reason` — `timeout`, `error` или `delegate_fail_open`.

Метрика учитывает каждый случай деградации. Собственные WARN обёртки ограничены общим интервалом на экземпляр, а не отдельным интервалом для каждого маршрута. Стандартный RedisRateLimiter может дополнительно писать собственные ERROR.

Обнаружение внутреннего fail-open делегата основано на `allowed=true` и `X-RateLimit-Remaining: -1`. Оно зависит от текущего поведения Gateway, стандартного имени заголовка и включённого `includeHeaders`.

### IP и reverse proxy

Сейчас `ipKeyResolver` использует `remoteAddress` и не доверяет произвольному `X-Forwarded-For`. При отсутствии адреса используется общий ключ `unknown`.

За reverse proxy/Ingress адресом клиента может оказаться адрес proxy. Перед таким размещением нужно отдельно настроить доверенную цепочку proxy и получение клиентского IP. Пользователи за одним NAT также делят IP-лимит каталога.

## Circuit Breaker и таймауты

| Настройка | Значение |
|---|---|
| HTTP connect timeout | 2000 мс |
| HTTP response timeout | 5 с |
| TimeLimiter для основных CB | 4 с |
| Тип и размер окна CB | COUNT_BASED, 10 |
| Минимальное число вызовов | 10 |
| Порог ошибок | 50% |
| Порог медленного вызова | 2 с |
| Порог медленных вызовов | 50% |
| Ожидание в OPEN | 10 с |
| Разрешённых вызовов в HALF_OPEN | 3 |

HTTP response timeout и TimeLimiter — разные ограничения; в обычном сценарии TimeLimiter может завершить операцию раньше HTTP response timeout.

| Маршрут | Circuit Breaker | Внутренний fallback |
|---|---|---|
| Catalog | `catalogCircuitBreaker` | `forward:/fallback/catalog` |
| Order | `orderCircuitBreaker` | `forward:/fallback/order` |

На основных маршрутах `statusCodes: "500,502,503,504"`. Эти ответы учитываются как сбои CB и заменяются fallback. Клиентские `4xx` передаются без преобразования Circuit Breaker и не считаются ошибками по этому списку; длительность вызова при этом всё равно может влиять на статистику медленных вызовов.

| Сценарий | Ответ fallback |
|---|---|
| Downstream вернул 500, 502 или 503 | 503, сервисный код недоступности |
| Downstream вернул 504 | 504, сервисный код таймаута |
| Таймаут соединения/выполнения | 504 |
| Ошибка соединения или открытый CB | 503 |

При таймауте POST отмена ожидания Gateway не гарантирует отмену операции в Order Service. Ответ содержит предупреждение о возможном завершении операции; повтор создания заказа выполняется с тем же `Idempotency-Key`.

### Диагностический маршрут

`LocalDebugRoutesConfig` активен только с профилем `local`:

- маршрут `GET /debug/slow` ведёт на `http://localhost:8081`;
- используется отдельный `catalogDebugCircuitBreaker`;
- настройки CB находятся в [application-local.yml](src/main/resources/application-local.yml);
- для debug-маршрута сейчас настроен HTTP-статус ошибки `503`, а не полный список основных маршрутов;
- диагностические вызовы не изменяют статистику `catalogCircuitBreaker`.

Сам endpoint должен существовать в запущенном Catalog Service. Этот README описывает только конфигурацию Gateway.

## Correlation ID и исходный путь

[CorrelationIdFilter](src/main/java/ru/ticketcraft/gateway/filter/CorrelationIdFilter.java) — ранний WebFilter, который выполняется до Spring Security.

- Принимается ровно одно значение `X-Correlation-ID`, соответствующее `[A-Za-z0-9._-]{1,128}`.
- Отсутствующее, пустое, некорректное или неоднозначное значение заменяется UUID.
- ID передаётся downstream и записывается в ответ через `beforeCommit`.
- ID сохраняется в атрибуте exchange для формирования ошибок.
- Исходный путь без query-параметров сохраняется до маршрутизации и внутреннего forward.

Поэтому сформированные Gateway ошибки, включая `401/403` и fallback при открытом CB, могут содержать исходный путь и согласованный correlation ID.

`X-Correlation-ID` — прикладной идентификатор; он не равен OpenTelemetry `traceId`.

## Формат ошибок

[GatewayProblemWriter](src/main/java/ru/ticketcraft/gateway/exception/GatewayProblemWriter.java) записывает `application/problem+json` через Jackson 3.

```json
{
  "type": "about:blank",
  "title": "Gateway Timeout",
  "status": 504,
  "detail": "Catalog service did not respond in time.",
  "instance": "/api/v1/catalog/events",
  "code": "CATALOG_TIMEOUT",
  "correlationId": "request-123"
}
```

| Источник | HTTP | code |
|---|---:|---|
| Security: отсутствующий или невалидный токен | 401 | UNAUTHORIZED |
| Security: запрет доступа | 403 | FORBIDDEN |
| Rate limiter: пустой ключ | 403 | RATE_LIMIT_KEY_MISSING |
| Rate limiter: исчерпан лимит | 429 | RATE_LIMIT_EXCEEDED |
| Fallback каталога | 503 / 504 | CATALOG_UNAVAILABLE / CATALOG_TIMEOUT |
| Fallback заказов | 503 / 504 | ORDER_SERVICE_UNAVAILABLE / ORDER_SERVICE_TIMEOUT |
| Обработчик ошибок соединения/таймаута | 503 / 504 | SERVICE_UNAVAILABLE / GATEWAY_TIMEOUT |

Bearer-обработчики сохраняют стандартный заголовок `WWW-Authenticate`. Writer не переписывает уже отправленный ответ; неизвестные исключения `GatewayExceptionHandler` передаёт дальше.

Контракт относится к перечисленным обработчикам Gateway. Он не означает, что любые ошибки сервера или CORS автоматически преобразуются в ProblemDetail. Бизнес-ответы downstream обычно передаются как есть, за исключением серверных статусов, явно включённых в Circuit Breaker.

## Конфигурация окружения

| Параметр | Значение по умолчанию |
|---|---|
| `ticketcraft.gateway.catalog-uri` | `http://localhost:8081` |
| `ticketcraft.gateway.order-uri` | `http://localhost:8082` |
| `spring.data.redis.host` / `port` | `localhost` / `6379` |
| `spring.data.redis.timeout` | `500ms` |
| `ticketcraft.gateway.rate-limiter.timeout` | `500ms` |
| `ticketcraft.gateway.rate-limiter.warn-interval` | `30s` |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | `http://localhost:9000/realms/ticketcraft` |
| `management.opentelemetry.tracing.export.otlp.endpoint` | `http://localhost:4318/v1/traces` |
| `management.tracing.sampling.probability` | `1.0` |

Значения можно переопределять стандартными средствами Spring Boot: аргументами запуска, переменными окружения и конфигурацией профиля. Адрес debug-маршрута пока задан непосредственно в Java-конфигурации.

## Сборка и запуск

Команды ниже выполняются в PowerShell из корня репозитория. Требуются JDK 21 и Docker Desktop либо другой поддержанный Testcontainers Docker runtime. Maven Wrapper скачивает Maven при первом запуске.

Для ручной работы подними Redis, Keycloak и нужные downstream-сервисы. Для экспорта трасс нужен Tempo. Инфраструктура описана в [docker-compose.yml](../docker-compose.yml); Java-приложения запускаются отдельно. Дополнительные инструкции — в [корневом README](../README.md).

Полная проверка модуля и сборка исполняемого JAR:

```powershell
.\mvnw.cmd -pl api-gateway clean verify
```

Compiler использует Java release 21 и сохраняет имена параметров. Surefire запускает тесты, а Spring Boot Maven Plugin выполняет `repackage` на фазе `package`.

Запуск:

```powershell
java -jar .\api-gateway\target\api-gateway-1.0.0-SNAPSHOT.jar
```

С диагностическим маршрутом:

```powershell
java -jar .\api-gateway\target\api-gateway-1.0.0-SNAPSHOT.jar --spring.profiles.active=local
```

Без запущенного OTLP-приёмника экспорт трасс можно отключить только для этого запуска:

```powershell
java -jar .\api-gateway\target\api-gateway-1.0.0-SNAPSHOT.jar --management.tracing.export.otlp.enabled=false
```

## Тесты

Исходники находятся в [src/test/java](src/test/java).

| Область | Проверки |
|---|---|
| CORS | Разрешённый/запрещённый origin, preflight создания заказа с Idempotency-Key |
| Security | 401 без токена и с невалидным токеном, 403 на запрещённом пути, WWW-Authenticate, correlation ID |
| Correlation ID | Генерация и валидация ID, несколько значений, передача downstream и замена заголовка ответа |
| ProblemDetail | Сериализация, верхний уровень дополнительных полей, исходный путь, committed response |
| Обработчик исключений и fallback | Классификация 503/504, вложенные таймауты, предупреждение для операций заказа |
| Порядок фильтров | Локальный 429 не вызывает downstream и не учитывается CB |
| Fallback через HTTP | Серверные статусы downstream, таймауты, открытый CB, исходный путь, POST без retry, передача проверенных 4xx |
| Обёртка rate limiter | Разрешение/отказ, ошибки, timeout, внутренний fail-open, счётчики |
| Настоящий Redis | Настройки из YAML, отдельные лимиты пользователей, локальный 429, fail-open при CLIENT PAUSE |

HTTP-интеграционные тесты используют локальные заглушки downstream. Security-тесты подменяют JWT decoder и не проверяют криптографическую подпись или реальный Keycloak.

`RedisRateLimiterIntegrationTest` запускает собственный `redis:7.4-alpine` на случайном порту. Локальный Redis на 6379 для него не нужен; Docker нужен. Тест создаёт пустую корзину напрямую в Redis, поэтому его fixture зависит от формата ключей RedisRateLimiter. Задержка Redis моделируется через `CLIENT PAUSE`, после чего выполняется `CLIENT UNPAUSE`.

В Redis-тесте отключён OTLP-экспорт. Redis-клиент останавливается перед завершением контейнера, контекст помечен `@DirtiesContext`. WARN с `reason=timeout` в сценарии fail-open ожидаем.

Запуск отдельных групп:

```powershell
.\mvnw.cmd -pl api-gateway test "-Dtest=GatewaySecurityIntegrationTest,GatewayFallbackIntegrationTest"
.\mvnw.cmd -pl api-gateway test "-Dtest=RedisRateLimiterIntegrationTest"
```

Пока не добавлены отдельные проверки двух экземпляров Gateway, полного цикла восстановления CB HALF_OPEN → CLOSED и реальной валидации JWT через Keycloak/JWK.

## Наблюдаемость

- Actuator exposure: `health,info,prometheus,metrics`. Security публично разрешает только GET `/actuator/health` и `/actuator/prometheus`; остальные endpoints из exposure не становятся публичными автоматически.
- Prometheus опрашивает `host.docker.internal:8080/actuator/prometheus` с job `api-gateway`: [конфигурация](../observability/prometheus/prometheus.yml).
- Grafana dashboard: [api-gateway.json](../observability/grafana/dashboards/api-gateway.json).
- OpenTelemetry отправляет трассы по OTLP HTTP в Tempo на `http://localhost:4318/v1/traces`.
- `spring.reactor.context-propagation: auto` включает автоматическую передачу контекста Reactor.
- OTLP-экспорт метрик отключён; метрики собирает Prometheus.

```powershell
curl.exe -i http://localhost:8080/actuator/health
curl.exe -s http://localhost:8080/actuator/prometheus |
    Select-String 'ticketcraft_gateway_rate_limiter_fail_open|resilience4j_circuitbreaker'
```

Статус health определяется настроенными индикаторами; он не является проверкой каждого HTTP downstream и всего бизнес-сценария.

## Ручная проверка

Публичный каталог:

```powershell
curl.exe -i http://localhost:8080/api/v1/catalog/events
```

Закрытый API без токена — ожидаются 401, ProblemDetail и X-Correlation-ID:

```powershell
curl.exe -i http://localhost:8080/api/v1/orders/1
```

Preflight создания заказа:

```powershell
curl.exe -i -X OPTIONS http://localhost:8080/api/v1/orders `
    -H "Origin: http://localhost:3000" `
    -H "Access-Control-Request-Method: POST" `
    -H "Access-Control-Request-Headers: authorization,content-type,idempotency-key,x-correlation-id"
```

Для вызова API заказов с токеном, полученным из Keycloak:

```powershell
$accessToken = Read-Host "Bearer token"
curl.exe -i http://localhost:8080/api/v1/orders/1 `
    -H "Authorization: Bearer $accessToken" `
    -H "X-Correlation-ID: manual-order-check"
```

Для получения успешного бизнес-ответа токен должен также удовлетворять правилам Order Service.

## Оставшиеся задачи

- Ограничить внешний доступ к `/actuator/prometheus` и прямые вызовы `/fallback/**`, сохранив внутренний forward; сейчас эти правила намеренно не изменены.
- Настроить получение клиентского IP для доверенной цепочки reverse proxy/Ingress.
- Проверить несколько экземпляров Gateway с общим Redis и реальное восстановление CB.
- Добавить сценарии реального JWT/JWK, ротации ключей и недоступности Keycloak.
- Настроить histogram latency, SLO-панели и алерты на 5xx, задержки, OPEN и fail-open Redis.
- Завершить эксплуатационную конфигурацию: readiness/liveness, TLS на внешней границе, sampling для production и нагрузочные проверки.

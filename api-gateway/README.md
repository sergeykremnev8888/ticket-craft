# API Gateway

Единая точка входа в HTTP API Ticket Craft. Направляет запросы в Catalog Service и Order Service, проверяет JWT и ограничивает частоту обращений.

[← Обзор проекта и быстрый старт](../README.md)

## Роль в архитектуре

Gateway принимает клиентские запросы и передаёт их бизнес-сервисам:

- просмотр мероприятий и билетов — в Catalog Service;
- создание и просмотр заказов — в Order Service.

Для проверки JWT используются ключи Keycloak.
Redis хранит состояние ограничителей частоты запросов.

Бизнес-правила покупки и проверка принадлежности заказа пользователю
выполняются в соответствующих сервисах.

## Технологии

Java 21, Spring Boot, Spring Cloud Gateway / WebFlux,
Spring Security, Redis, Resilience4j, Actuator,
Micrometer и OpenTelemetry.

## Основные возможности

- Маршрутизация клиентских запросов.
- Проверка JWT, issuer и audience.
- Rate limiting по IP для каталога и по пользователю для заказов.
- Circuit Breaker и таймауты обращений к бизнес-сервисам.
- Настройка CORS.
- Передача correlation ID.
- Ответы об ошибках в формате Problem Detail.
- Метрики и распределённая трассировка.

## API и проверка

HTTP-порт — `8080`.

| Метод | Путь | Назначение | Авторизация |
|---|---|---|---|
| `GET` | `/api/v1/catalog/events` | Список мероприятий | Не требуется |
| `GET` | `/api/v1/catalog/events/{eventId}` | Мероприятие и билеты | Не требуется |
| `POST` | `/api/v1/orders` | Создание заказа | JWT |
| `GET` | `/api/v1/orders/{orderId}` | Просмотр своего заказа | JWT |

Для создания заказа требуется `Idempotency-Key`.
Проверка scopes выполняется в Order Service.

Проверка готовности Gateway:

```powershell
curl.exe -i http://localhost:8080/actuator/health
```

Проверка маршрута каталога:

```powershell
curl.exe -i http://localhost:8080/api/v1/catalog/events
```

Настройка авторизации в Postman и полный сценарий покупки описаны
в [корневом README](../README.md#проверка-работоспособности).

## Запуск

Запуск всего проекта:

```powershell
docker compose up -d --build
```

Перед первым запуском подготовьте `.env` по
[инструкции](../README.md#быстрый-старт).

Просмотр состояния и логов:

```powershell
docker compose ps api-gateway
docker compose logs --tail 100 api-gateway
```

Основные переменные окружения:
`GATEWAY_CATALOG_URI`, `GATEWAY_ORDER_URI`, `REDIS_URL`,
`JWT_ISSUER_URI`, `JWT_JWK_SET_URI`, `JWT_AUDIENCE`.

Значения для Docker-окружения приведены в
[`.env.example`](../.env.example).

## Тесты

Нужны JDK 21 и работающий Docker.

```powershell
.\mvnw.cmd -pl api-gateway -am test
```

Тесты проверяют маршрутизацию, безопасность, обработку ошибок,
rate limiting и отказоустойчивость.

## Ограничения

- Gateway не управляет бизнес-транзакциями и идемпотентностью заказов.
- При размещении за reverse proxy требуется отдельно настроить
  доверие к передаваемому IP клиента.
- Ограничение доступа к диагностическим endpoint и production-настройки
  внешней границы требуют отдельной конфигурации.

# Catalog Service

Сервис каталога мероприятий и билетов. Хранит собственные данные в PostgreSQL `catalog_db` и отвечает за атомарное резервирование, подтверждение и освобождение билетов.

[← Обзор проекта](../README.md)

## API

| Метод | Endpoint | Описание |
|---|---|---|
| `GET` | `/api/v1/catalog/events` | Список мероприятий |
| `GET` | `/api/v1/catalog/events/{eventId}` | Детали мероприятия и билеты |

Локальный порт: **8081**. Публичные запросы к каталогу разрешены правилами security сервиса. В коде также есть диагностический `DebugController`: его не следует рассматривать как публичный production API.

## Kafka и резервирование

- Читает команды из `ticket-reservation-commands` (`ReserveTicketCommand`, `ConfirmTicketCommand`, `ReleaseTicketCommand`).
- Публикует результаты в `ticket-reservation-results` через **Transactional Outbox**: `TicketReservedEvent`, `TicketReservationFailedEvent`, `TicketConfirmedEvent`, `TicketConfirmationFailedEvent`, `TicketReleasedEvent`.
- В `TicketReservationService` резервирование выполняется атомарным SQL-обновлением с проверкой состояния билета. Повторная обработка команд учитывается по идентификаторам сообщений и состоянию резерва.
- Резерв имеет срок действия; отдельный scheduler обрабатывает истечение времени резервирования.
- Kafka consumer настроен с обработкой ошибок и DLT для команд.

При успешном резервировании `TicketReservedEvent` содержит **авторитетные** `eventId` и `price`, прочитанные из `catalog_db`. Это исключает доверие к цене из клиентского запроса.

## Хранение и технологии

- Spring Data JPA, PostgreSQL 16, Flyway (`src/main/resources/db/migration`).
- Кеширование данных каталога и Redis-based rate limiting присутствуют в реализации; детали и параметры — в `application.yml` и классах `config/`, `ratelimit/`.
- Outbox publisher использует выборку/захват записей, повторные попытки и метрики.
- JWT/OAuth2 Resource Server для защищённых маршрутов; публичный каталог доступен без токена.

## Запуск и тесты

```powershell
# Из корня репозитория, после запуска docker compose
.\mvnw.cmd -pl catalog-service -am test
.\mvnw.cmd -pl catalog-service spring-boot:run
```

Порт БД с хоста: `5432`. Профили и параметры подключения: `application-local.yml`, `application-prod.yml`.

## Важные ограничения

- Доступность билетов — изменяемое состояние; детальный ответ мероприятия намеренно не кешируется как долгоживущий снимок наличия билетов.
- Формат `TicketReservedEvent` был расширен полями `eventId` и `price`. При обновлении сначала следует развернуть новый Catalog, дождаться обработки сообщений старого формата в `ticket-reservation-results`, затем обновить Order; автоматического Schema Registry/versioning в проекте нет.

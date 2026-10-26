# Catalog Service

Сервис мероприятий и билетов Ticket Craft. Предоставляет каталог,
резервирует билеты и подтверждает их продажу в процессе покупки.

[← Обзор проекта и быстрый старт](../README.md)

## Роль в архитектуре

Клиент читает каталог через API Gateway.
Команды резервирования, подтверждения и освобождения билета
сервис получает асинхронно через Kafka.

Catalog Service владеет собственной PostgreSQL-базой.
Redis используется для кеширования каталога.

Результаты обработки сохраняются вместе с бизнес-изменениями
через Transactional Outbox и публикуются через Debezium.

## Технологии

Java 21, Spring Boot, Spring MVC, Spring Security,
Spring Data JPA, PostgreSQL, Flyway, Spring Kafka,
Redis, Actuator, Micrometer и OpenTelemetry.

## Основные возможности

- Просмотр мероприятий и доступных билетов.
- Кеширование каталога в Redis.
- Конкурентное резервирование билетов на стороне PostgreSQL.
- Подтверждение продажи и освобождение резервов.
- Обработка истечения срока резервирования.
- Защита от повторной обработки команд.
- Transactional Outbox, retry и Dead Letter Queue.
- Метрики и распределённая трассировка.

## API и проверка

HTTP-порт — `8081`.
Клиентские запросы отправляются через Gateway на порту `8080`.

| Метод | Путь через Gateway | Назначение |
|---|---|---|
| `GET` | `/api/v1/catalog/events` | Список мероприятий |
| `GET` | `/api/v1/catalog/events/{eventId}` | Мероприятие и билеты |

Чтение каталога не требует авторизации.

Проверка готовности сервиса:

```powershell
curl.exe -i http://localhost:8081/actuator/health
```

Проверка demo-мероприятия через Gateway:

```powershell
curl.exe -i http://localhost:8080/api/v1/catalog/events/11111111-1111-1111-1111-111111111111
```

После чистого запуска Compose автоматически загружает
одно мероприятие и десять билетов.

## Запуск

Запуск всего проекта:

```powershell
docker compose up -d --build
```

Перед первым запуском подготовьте `.env` по
[инструкции](../README.md#быстрый-старт).

Миграции, загрузка demo-данных и регистрация Debezium-коннектора
выполняются автоматически.

Просмотр состояния и логов:

```powershell
docker compose ps catalog-service
docker compose logs --tail 100 catalog-service catalog-demo-data register-catalog-outbox
```

Параметры подключения к PostgreSQL, Kafka и Redis задаются
в [`.env.example`](../.env.example) и Docker Compose.

## Тесты

Нужны JDK 21 и работающий Docker.

```powershell
.\mvnw.cmd -pl catalog-service -am test
```

Тесты проверяют резервирование, конкурентный доступ,
обработку повторных команд, транзакции и безопасность.

## Ограничения

- Повторная загрузка demo-данных не сбрасывает состояние проданных билетов.
- Изменения каталога могут отображаться после истечения срока кеша.
- Восстановление CDC после потери offsets или replication slot
  требует отдельной процедуры.
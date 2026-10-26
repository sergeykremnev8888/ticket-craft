# Payment Service

Внутренний сервис оплаты и полного возврата платежей Ticket Craft.
Получает запросы через Kafka, обращается к платёжному провайдеру
и передаёт результат в процесс покупки.

[← Обзор проекта и быстрый старт](../README.md)

## Роль в архитектуре

Payment Service взаимодействует с Order Service асинхронно через Kafka.
К платёжному провайдеру обращается по HTTP.

Сервис владеет собственной PostgreSQL-базой.
Результаты обработки сохраняются через Transactional Outbox
и публикуются через Debezium.

В demo-окружении используется `payment-provider-stub`,
который запускается вместе с проектом через Docker Compose.
Реальные списания денег не выполняются.

## Технологии

Java 21, Spring Boot, Spring JDBC, PostgreSQL,
Flyway, Spring Kafka, HTTP-клиент,
Actuator, Micrometer и OpenTelemetry.

## Основные возможности

- Проведение оплаты и полного возврата.
- Идемпотентная обработка запросов оплаты.
- Валидация денежных значений.
- Таймауты обращений к провайдеру.
- Проверка результата операции после некоторых ошибок HTTP-вызова.
- Transactional Outbox, retry и Dead Letter Queue.
- Метрики и распределённая трассировка.

## API и проверка

HTTP-порт — `8084`.

Клиентского REST API для оплаты нет:
сервис получает бизнес-запросы через Kafka.
HTTP-сервер используется для Actuator.

Проверка готовности:

```powershell
curl.exe -i http://localhost:8084/actuator/health
```

Основная проверка работы — создание заказа через Gateway
и переход заказа в `CONFIRMED`.

Полный сценарий описан в
[корневом README](../README.md#проверка-работоспособности).

## Запуск

Запуск всего проекта, включая платёжный эмулятор:

```powershell
docker compose up -d --build
```

Перед первым запуском подготовьте `.env` по
[инструкции](../README.md#быстрый-старт).

Миграции и регистрация Debezium-коннектора выполняются автоматически.

Адрес провайдера в Docker-окружении:

```dotenv
PAYMENT_PROVIDER_BASE_URL=http://payment-provider-stub:8090
```

Эмулятор запускается из готового JAR,
расположенного в [docker/payment-provider-stub](../docker/payment-provider-stub).

Просмотр состояния и логов:

```powershell
docker compose ps payment-service payment-provider-stub
docker compose logs --tail 100 payment-service payment-provider-stub register-payment-outbox
```

Остальные параметры окружения приведены в
[`.env.example`](../.env.example).

## Тесты

Нужны JDK 21 и работающий Docker.

```powershell
.\mvnw.cmd -pl payment-service -am test
```

Тесты проверяют взаимодействие с провайдером,
валидацию, обработку ошибок, компенсации и публикацию результатов.

## Ограничения

- Частичные возвраты не реализованы.
- Фоновая сверка неопределённых операций с провайдером не реализована.
- После исчерпания повторных попыток может потребоваться
  разбор DLT и контролируемая повторная обработка.

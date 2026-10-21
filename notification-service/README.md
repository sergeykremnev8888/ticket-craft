# Notification Service

Внутренний Kafka consumer событий подтверждённых заказов. Владеет PostgreSQL `notification_db` и реализует **персистентную идемпотентность обработки**.

[← Обзор проекта](../README.md)

## Обработка событий

- `NotificationConsumer` слушает топик `order-events` (через `ticketcraft.kafka.topic.source-topic`), consumer group по умолчанию — `notification-group`.
- После получения `OrderEvent` вызывается `IdempotentNotificationProcessor`.
- В одной локальной транзакции выполняется `ProcessedEventRepository.insertIfAbsent(messageId)` и бизнес-обработка.
- Повторный `messageId` пропускается; Kafka offset подтверждается **после** успешного завершения обработки.
- Ошибки обрабатываются через настроенные Kafka retry/DLT механизмы.

**Важно:** текущая реализация `NotificationService` **логирует** уведомление о подтверждённом заказе. Отправка настоящих email, SMS или push через внешнего провайдера **не реализована**. Поэтому текущая транзакционная модель не должна интерпретироваться как гарантия exactly-once доставки внешнего уведомления.

## API и данные

Клиентского REST API нет. Локальный порт: **8083** (Actuator). База `notification_db` доступна с хоста на `5434`; миграции Flyway находятся в `src/main/resources/db/migration`.

## Запуск и тесты

```powershell
# Из корня репозитория
.\mvnw.cmd -pl notification-service -am test
.\mvnw.cmd -pl notification-service spring-boot:run
```

Для обработки событий должны быть доступны Kafka, PostgreSQL и публикующий `order-service`.

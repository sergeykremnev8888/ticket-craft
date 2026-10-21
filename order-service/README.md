# Order Service

Сервис создания заказов и **оркестратор асинхронной Saga** бронирования и покупки билетов. Владеет PostgreSQL `order_db`; использует Spring Data JDBC и Kafka.

[← Обзор проекта](../README.md)

## HTTP API

| Метод | Endpoint | Условия |
|---|---|---|
| `POST` | `/api/v1/orders` | JWT со scope `orders.write`, `Idempotency-Key`, body `{"ticketId":"<uuid>"}` |
| `GET` | `/api/v1/orders/{orderId}` | JWT со scope `orders.read`, доступ только к своему заказу |

## Saga и сообщения

1. В транзакции создаются заказ, `OrderSaga` и outbox-команда `ReserveTicketCommand`.
2. Результат резервирования поступает из `ticket-reservation-results`; при успехе создаётся `PaymentRequestedEvent`.
3. `payment-service` отвечает через `payment-results`; после успеха оплаты Order отправляет `ConfirmTicketCommand`.
4. После подтверждения билета заказ переходит в `CONFIRMED`, публикуется `OrderEvent` в `order-events`.
5. При неуспешных ветках используются компенсации: освобождение резерва и, если необходимо, возврат оплаты через `RefundPaymentCommand`.

Основные топики: `ticket-reservation-commands`, `ticket-reservation-results`, `payment-requests`, `payment-commands`, `payment-results`, `order-events`; DLT-настройки см. `application.yml`.

## Надёжность

- **HTTP idempotency:** `Idempotency-Key`, хеш запроса и хранение ключей в PostgreSQL.
- **Transactional Outbox:** события фиксируются вместе с бизнес-изменениями, отправляются отдельным publisher.
- **Consumer idempotency:** `ProcessedEventRepository` предотвращает повторное применение обработанных результатов.
- **Saga state machine:** переходы состояния заказа и Saga проверяются в транзакционных процессорах.
- **Security:** JWT Resource Server, scopes, проверка владельца заказа.

## Запуск и тесты

```powershell
# Из корня репозитория
.\mvnw.cmd -pl order-service -am test
.\mvnw.cmd -pl order-service spring-boot:run
```

PostgreSQL доступен с хоста на `5433`. Схема БД — Flyway migrations в `src/main/resources/db/migration`.

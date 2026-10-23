# Common DTO

Общий Maven-модуль с контрактами сообщений для взаимодействия сервисов Ticket Craft через Kafka. **Не является самостоятельным HTTP-сервисом** и не запускается как приложение.

[← Обзор проекта](../README.md)

## Контракты

- Резервирование: `ReserveTicketCommand`, `TicketReservedEvent`, `TicketReservationFailedEvent`.
- Подтверждение и освобождение: `ConfirmTicketCommand`, `TicketConfirmedEvent`, `TicketConfirmationFailedEvent`, `ReleaseTicketCommand`, `TicketReleasedEvent`.
- Платежи: `PaymentRequestedEvent`, `PaymentSucceededEvent`, `PaymentFailedEvent`, `RefundPaymentCommand`, `PaymentRefundedEvent`.
- Заказы и состояния: `OrderEvent`, `OrderState`, `TicketStatus`, `TicketConfirmationFailureReason`.

Файлы: `src/main/java/ru/ticketcraft/dto/`.

## Использование

Модуль подключается Maven-зависимостью в сервисах и собирается вместе с остальными модулями из корневого `pom.xml`.

```powershell
.\mvnw.cmd -pl common-dto -am test
```

## Ограничения подхода

Общий JAR упрощает учебный проект, но создаёт **связанность версий** producer и consumer. Изменение структуры события может быть несовместимым с сообщениями, уже лежащими в Kafka. В частности, изменение `TicketReservedEvent` требует согласованных версий Catalog и Order и отдельного плана обработки сообщений старого формата. Безопасный rolling deployment разных версий контрактов не гарантируется.

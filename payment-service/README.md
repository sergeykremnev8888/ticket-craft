# Payment Service

Внутренний Kafka-driven сервис оплаты и возвратов. Владеет PostgreSQL `payment_db`; взаимодействует с **внешним HTTP payment provider** через `PaymentGateway` / `HttpPaymentGateway`.

[← Обзор проекта](../README.md)

## Назначение и взаимодействие

- Обрабатывает `PaymentRequestedEvent` из Kafka и создаёт/находит запись платежа.
- Вызывает `charge` у внешнего провайдера, сохраняет итог и формирует `PaymentSucceededEvent` либо `PaymentFailedEvent`.
- Обрабатывает `RefundPaymentCommand`; при успешном возврате формирует `PaymentRefundedEvent`.
- Результаты сохраняются через собственный **Transactional Outbox** и публикуются в Kafka.
- Для повторных запросов проверяет существующий статус платежа; повторный успешный платёж не должен вызывать повторное списание в рамках реализованной модели состояния.

Входящие топики настраиваются в `PaymentKafkaProperties` / `application.yml`; в общей схеме используются `payment-requests` и `payment-commands`, исходящий — `payment-results`.

## Важная семантика ошибок

- Бизнес-отказ провайдера (`DECLINED`) трактуется как результат оплаты и приводит к событию неуспешного платежа.
- Transport/HTTP 5xx и неуспешный refund — технические ошибки; consumer использует retry/DLT, а компенсация не считается завершённой.
- Вызов внешнего провайдера не является частью общей распределённой транзакции с Order и Catalog.

## HTTP и конфигурация

Сервис **не публикует бизнес REST API** для клиентов. Порт **8084** используется в том числе для Actuator. Для локального запуска внешний провайдер должен отвечать по `PAYMENT_PROVIDER_BASE_URL` (по умолчанию `http://localhost:8090`); Docker Compose **не запускает** его.

Параметры путей charge/refund и timeout см. `.env.example` и `application*.yml`. PostgreSQL с хоста: `5435`, миграции Flyway — `src/main/resources/db/migration`.

## Запуск и тесты

```powershell
# Из корня репозитория
.\mvnw.cmd -pl payment-service -am test
.\mvnw.cmd -pl payment-service spring-boot:run
```

Для полного успешного end-to-end сценария необходим совместимый payment provider; наличие Java-сервиса и Kafka само по себе недостаточно.

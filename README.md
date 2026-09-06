# Ticket Craft

Учебный проект системы бронирования и покупки билетов, построенный в виде набора микросервисов.

Проект предназначен не только для демонстрации Spring Boot и Kafka, но и как практическая площадка для изучения проблем, которые возникают в распределённых системах:

- конкурентное бронирование одного ресурса;
- согласованность данных между микросервисами;
- Kafka и асинхронное взаимодействие;
- идемпотентность;
- транзакции;
- N+1;
- работа с PostgreSQL под нагрузкой;
- отказоустойчивость;
- observability;
- подготовка приложения к highload.

> **Статус проекта:** учебный production-like проект.
> Некоторые механизмы намеренно реализованы в упрощённом виде и будут последовательно улучшаться в рамках roadmap.

---

## Архитектура

Система состоит из трёх микросервисов:

- `catalog-service` — каталог мероприятий и билетов;
- `order-service` — создание заказов;
- `notification-service` — обработка событий заказов и отправка уведомлений.

Каждый сервис владеет собственной базой данных.

```text
                         ┌──────────────────┐
                         │      Client      │
                         └────────┬─────────┘
                                  │
                    ┌─────────────┴─────────────┐
                    │                           │
                    ▼                           ▼
          ┌──────────────────┐        ┌──────────────────┐
          │ catalog-service  │◄──────►│   order-service  │
          │     :8081        │  REST  │      :8082       │
          └────────┬─────────┘        └───────┬──────────┘
                   │                          │
                   ▼                          ▼
          ┌──────────────────┐       ┌───────────────────┐
          │   catalog_db     │       │     order_db      │
          │   PostgreSQL     │       │    PostgreSQL     │
          └──────────────────┘       └─────────┬─────────┘
                                               │
                                               │ Kafka
                                               ▼
                                     ┌────────────────────┐
                                     │ notification-service│
                                     │       :8083        │
                                     └────────────────────┘
```

### Database-per-Service

Каждый микросервис имеет собственную PostgreSQL database.

```text
catalog-service  ──► catalog_db
order-service    ──► order_db
```

Сервисы не используют общие таблицы и не обращаются напрямую к базе данных другого сервиса.

---

## Технологический стек

| Технология | Использование |
|---|---|
| Java 21 | основной язык |
| Spring Boot 4 | backend framework |
| Spring Web | REST API |
| Spring Data JPA | работа с каталогом |
| Spring Data JDBC | работа с заказами |
| Spring Kafka | взаимодействие через Kafka |
| PostgreSQL 16 | persistent storage |
| Apache Kafka | event-driven communication |
| Maven | сборка проекта |
| Docker Compose | локальная инфраструктура |
| Actuator | health/metrics endpoints |
| HikariCP | connection pool |

---

## Структура проекта

```text
ticket-craft/
│
├── common-dto/
│
├── catalog-service/
│   └── src/main/
│       ├── java/
│       └── resources/
│           ├── application.yml
│           ├── application-local.yml
│           └── application-prod.yml
│
├── order-service/
│   └── src/main/
│       ├── java/
│       └── resources/
│           ├── application.yml
│           ├── application-local.yml
│           └── application-prod.yml
│
├── notification-service/
│   └── src/main/
│       ├── java/
│       └── resources/
│           ├── application.yml
│           ├── application-local.yml
│           └── application-prod.yml
│
├── docker-compose.yml
├── pgadmin-servers.json
├── pom.xml
└── README.md
```

---

# Сервисы

## catalog-service

Отвечает за:

- мероприятия;
- билеты;
- доступность билетов;
- резервирование билета.

Порт:

```text
8081
```

Основная база:

```text
catalog_db
```

---

## order-service

Отвечает за:

- создание заказа;
- хранение заказа;
- взаимодействие с `catalog-service`;
- публикацию событий заказа в Kafka.

Порт:

```text
8082
```

Основная база:

```text
order_db
```

---

## notification-service

Получает события из Kafka и обрабатывает их.

Порт:

```text
8083
```

Основной Kafka consumer group:

```text
notification-group
```

---

# API

## Получить каталог мероприятий

```http
GET /api/v1/catalog/events
```

Пример:

```bash
curl http://localhost:8081/api/v1/catalog/events
```

---

## Зарезервировать билет

```http
POST /api/v1/catalog/tickets/{ticketId}/reserve
```

Пример:

```bash
curl -X POST \
  http://localhost:8081/api/v1/catalog/tickets/1/reserve
```

На текущем этапе резервирование использует pessimistic locking.

> В дальнейшем горячий путь бронирования будет переведён на атомарный conditional `UPDATE`.

---

## Создать заказ

```http
POST /api/v1/orders
```

Пример:

```json
{
  "userId": "user-123",
  "ticketId": 1
}
```

Пример запроса:

```bash
curl -X POST \
  http://localhost:8082/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user-123",
    "ticketId": 1
  }'
```

---

# Текущий flow создания заказа

На текущем этапе flow выглядит следующим образом:

```text
Client
  │
  │ POST /orders
  ▼
order-service
  │
  │ POST /catalog/tickets/{id}/reserve
  ▼
catalog-service
  │
  │ PostgreSQL transaction
  │ lock ticket
  │ check availability
  │ reserve ticket
  ▼
catalog_db
  │
  │ success
  ▼
order-service
  │
  │ save order
  ▼
order_db
  │
  │ publish OrderEvent
  ▼
Kafka
  │
  ▼
notification-service
```

---

# Kafka

Kafka используется для асинхронного взаимодействия между сервисами.

Текущая схема:

```text
order-service
      │
      │ OrderEvent
      ▼
   Kafka
      │
      ▼
notification-service
```

Producer использует:

```yaml
acks: all
enable-idempotence: true
```

Consumer использует:

```text
manual acknowledgement
```

и имеет базовую защиту от повторной обработки событий.

> Текущая реализация consumer idempotency хранит обработанные event ID в памяти процесса. Это означает, что информация теряется после restart. Персистентная идемпотентность будет добавлена в рамках отдельной задачи roadmap.

---

# PostgreSQL

Используются две независимые базы данных.

### Catalog

```text
localhost:5432
database: catalog_db
```

### Order

```text
localhost:5433
database: order_db
```

Для локальной разработки PostgreSQL запускается через Docker Compose.

---

# Конфигурация

Разделена на:

```text
application.yml
application-local.yml
application-prod.yml
```

### application.yml

Содержит общие настройки приложения:

- имя сервиса;
- порт;
- Actuator;
- базовые logging settings;
- настройки, одинаковые для разных окружений.

### application-local.yml

Используется для локальной разработки.

Например:

```text
localhost:5432
localhost:5433
localhost:9092
```

### application-prod.yml

Содержит production configuration contract.

Адреса инфраструктуры и credentials передаются через environment variables:

```text
CATALOG_DB_URL
CATALOG_DB_USERNAME
CATALOG_DB_PASSWORD
ORDER_DB_URL
ORDER_DB_USERNAME
ORDER_DB_PASSWORD
KAFKA_BOOTSTRAP_SERVERS
CATALOG_SERVICE_URL
```

Секреты не должны храниться в Git.

---

# Spring Profiles

Для локального запуска используется профиль:

```text
local
```

Production:

```text
prod
```

Например:

```bash
SPRING_PROFILES_ACTIVE=local
```

или:

```bash
SPRING_PROFILES_ACTIVE=prod
```

По умолчанию используется `local`, чтобы проект можно было запустить без дополнительной конфигурации.

---

# Переменные окружения

В репозитории должен находиться только пример конфигурации:

```text
.env.example
```

Например:

```dotenv
CATALOG_DB_NAME=catalog_db
CATALOG_DB_USERNAME=postgres
CATALOG_DB_PASSWORD=postgres

ORDER_DB_NAME=order_db
ORDER_DB_USERNAME=postgres
ORDER_DB_PASSWORD=postgres

PGADMIN_EMAIL=admin@example.com
PGADMIN_PASSWORD=change-me

KAFKA_BOOTSTRAP_SERVERS=localhost:9092
```

Файл с реальными credentials:

```text
.env
```

не должен попадать в Git.

---

# Database schema

На текущем этапе схема базы данных создаётся средствами приложения.

Для production configuration:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
```

Hibernate не должен создавать или удалять production schema.

Также отключён автоматический SQL initialization:

```yaml
spring:
  sql:
    init:
      mode: never
```

---

# Connection Pool

Для работы с PostgreSQL используется HikariCP.

Параметры pool являются конфигурируемыми.

Например:

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: ${DB_POOL_MAX_SIZE:30}
      minimum-idle: ${DB_POOL_MIN_IDLE:10}
      connection-timeout: ${DB_CONNECTION_TIMEOUT_MS:3000}
```

Размер connection pool не следует увеличивать бездумно.

Например, если запустить:

```text
10 application replicas
×
30 DB connections
=
300 connections
```

это уже необходимо сопоставлять с возможностями PostgreSQL.

---

# Docker Compose

Локальная инфраструктура запускается через:

```bash
docker compose up -d
```

Остановка:

```bash
docker compose down
```

Проверка контейнеров:

```bash
docker compose ps
```

---

# Локальная инфраструктура

Docker Compose поднимает:

```text
┌──────────────────────┐
│ PostgreSQL           │
│ catalog_db           │
│ :5432                │
└──────────────────────┘

┌──────────────────────┐
│ PostgreSQL           │
│ order_db             │
│ :5433                │
└──────────────────────┘

┌──────────────────────┐
│ Kafka                │
│ :9092                │
└──────────────────────┘

┌──────────────────────┐
│ Kafka UI             │
└──────────────────────┘

┌──────────────────────┐
│ pgAdmin              │
└──────────────────────┘
```

---

# Запуск проекта

## 1. Требования

Необходимы:

- JDK 21;
- Docker;
- Docker Compose;
- Maven.

Проверить Java:

```bash
java -version
```

Ожидается Java 21.

---

## 2. Запустить инфраструктуру

```bash
docker compose up -d
```

Проверить:

```bash
docker compose ps
```

---

## 3. Запустить catalog-service

```bash
./mvnw -pl catalog-service spring-boot:run
```

Windows:

```powershell
mvnw.cmd -pl catalog-service spring-boot:run
```

---

## 4. Запустить order-service

```bash
./mvnw -pl order-service spring-boot:run
```

---

## 5. Запустить notification-service

```bash
./mvnw -pl notification-service spring-boot:run
```

---

# Health Checks

Для сервисов включён Spring Boot Actuator.

Основной endpoint:

```http
GET /actuator/health
```

Например:

```bash
curl http://localhost:8081/actuator/health
```

Также подготовлен endpoint для Prometheus:

```http
GET /actuator/prometheus
```

---

# Текущие архитектурные паттерны

Проект демонстрирует следующие подходы:

### Database-per-Service

Каждый сервис владеет собственной базой.

### REST

Синхронное взаимодействие:

```text
order-service → catalog-service
```

### Event-Driven Architecture

Асинхронное взаимодействие:

```text
order-service → Kafka → notification-service
```

### Pessimistic Locking

Текущий механизм защиты от одновременного бронирования одного билета.

### N+1 demonstration

В `catalog-service` специально продемонстрирован N+1 problem.

### EntityGraph

Есть вариант загрузки связанных сущностей без N+1.

### Kafka Producer Idempotence

Producer настроен с:

```yaml
enable-idempotence: true
```

### Manual Consumer Acknowledgement

Kafka consumer использует manual acknowledgement.

---

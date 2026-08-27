# TicketCraft — Система бронирования и покупки билетов

**TicketCraft** — это демонстрационная распределенная микросервисная система продажи и бронирования билетов на мероприятия, спроектированная с учетом паттернов **Highload**, **Database-per-Service**, **Event-Driven Architecture** и демонстрации решений классических проблем работы с БД (N+1, пессимистические блокировки `SELECT ... FOR UPDATE`, идемпотентность консьюмеров).

---

## Технологический стек

* **Язык & Платформа:** Java 21 (LTS)
* **Фреймворк:** Spring Boot (Spring Web, Spring Data JPA, Spring Data JDBC, Spring Kafka)
* **Базы данных:** PostgreSQL 16 (изолированные БД `catalog_db` и `order_db` по паттерну *Database-per-Service*)
* **Брокер сообщений:** Apache Kafka (KRaft mode без ZooKeeper)
* **Инструменты & Сборка:** Apache Maven, Docker & Docker Compose, Kafka UI

---

## Архитектура и взаимодействие микросервисов

```
                    ┌─────────────────────────┐
                    │  Frontend / API Client  │
                    └────────────┬────────────┘
                                 │
          ┌──────────────────────┴──────────────────────┐
          │ HTTP (GET events / POST reserve)            │ HTTP (POST orders)
          ▼                                             ▼
┌───────────────────────────┐                 ┌───────────────────────────┐
│      catalog-service      │◄────────────────┤       order-service       │
│        (Port 8081)        │   HTTP Client   │        (Port 8082)        │
├───────────────────────────┤ (Pessimistic    ├───────────────────────────┤
│ Spring Data JPA/Hibernate │   Lock / REST)  │ Spring Data JDBC          │
│ PostgreSQL (Port 5432)    │                 │ PostgreSQL (Port 5433)    │
│ DB: catalog_db            │                 │ DB: order_db              │
└───────────────────────────┘                 └─────────────┬─────────────┘
                                                            │
                                                            │ Kafka Producer
                                                            │ Topic: order-events
                                                            ▼
                                              ┌───────────────────────────┐
                                              │   Apache Kafka (KRaft)    │
                                              │        (Port 9092)        │
                                              └─────────────┬─────────────┘
                                                            │
                                                            │ Kafka Consumer
                                                            ▼
                                              ┌───────────────────────────┐
                                              │   notification-service    │
                                              │        (Port 8083)        │
                                              │  Идемпотентный консьюмер  │
                                              └───────────────────────────┘
```

---

## Модули проекта

1. **`common-dto`** — общий модуль с иммутабельными Java Record DTO и событиями (`OrderEvent`, `OrderState`), используемый для сериализации/десериализации сообщений Kafka между сервисами.
2. **`catalog-service`** (`8081`) — сервис каталога мероприятий и билетов. Демонстрирует:
   - Сравнение ленивой загрузки (проблема N+1) и оптимизированного `EntityGraph` / `JOIN FETCH`.
   - Пессимистическую блокировку билета при резервации (`@Lock(LockModeType.PESSIMISTIC_WRITE)` / `SELECT ... FOR UPDATE`).
3. **`order-service`** (`8082`) — транзакционный сервис оформления заказов на базе Spring Data JDBC. Делегирует блокировку в `catalog-service`, сохраняет заказ в `order_db` и отправляет событие в Kafka.
4. **`notification-service`** (`8083`) — сервис нотификаций. Читает топик `order-events` с защитой от дубликатов сообщений (реестр идемпотентности).

---

## Пошаговая инструкция по локальному запуску

### 1. Предварительные требования
Убедитесь, что у вас установлены:
- **Java 21+** (`java -version`)
- **Maven 3.9+** (`mvn -v`)
- **Docker & Docker Compose** (`docker compose version`)

---

### 2. Запуск инфраструктуры в Docker

В корневой директории проекта выполните команду для поднятия контейнеров:

```bash
docker compose down -v   # Очистка предыдущих томов (при необходимости)
docker compose up -d
```

Проверьте статус контейнеров (`docker compose ps`):
* `postgres-catalog` (порт `5432`) — база данных `catalog_db`
* `postgres-order` (порт `5433`) — база данных `order_db`
* `kafka` (порт `9092`) — брокер Kafka в режиме KRaft
* `kafka-ui` (порт `8080`) — веб-панель управления: [http://localhost:8080](http://localhost:8080)

---

### 3. Сборка Maven-модулей

Соберите весь многомодульный проект и установите `common-dto` в локальный репозиторий `.m2`:

```bash
mvn clean install -DskipTests
```

---

### 4. Запуск микросервисов

Запустите каждый сервис в отдельном терминале (или через Run Configuration в вашей IDE):

#### 1. Catalog Service (порт `8081`):
```bash
mvn spring-boot:run -pl catalog-service
```
*База `catalog_db` автоматически создается Hibernate (`ddl-auto: create-drop`) и наполняется тестовыми данными из `data.sql`.*

#### 2. Order Service (порт `8082`):
```bash
mvn spring-boot:run -pl order-service
```
*Подключается к `order_db` и автоматически применяет схему таблиц из `schema.sql`.*

#### 3. Notification Service (порт `8083`):
```bash
mvn spring-boot:run -pl notification-service
```
*Подключается к брокеру Kafka и слушает топик `order-events`.*

---

## Сценарии сквозного тестирования (E2E)

### Сценарий 1. Демонстрация проблемы N+1 vs Оптимизированный запрос

* **Запрос с N+1 проблемой:**
  ```bash
  curl -X GET http://localhost:8081/api/v1/catalog/events-lazy
  ```
  *(В логах `catalog-service` появится 1 SQL-запрос для выборки событий + N запросов для выборки билетов каждого мероприятия).*

* **Оптимизированный запрос (Решение N+1):**
  ```bash
  curl -X GET http://localhost:8081/api/v1/catalog/events-optimized
  ```
  *(В логах выполнится ровно 1 SQL-запрос с `LEFT OUTER JOIN`).*

---

### Сценарий 2. Успешное оформление заказа и резервация билета

Отправьте POST-запрос на покупку доступного билета №1:
```bash
curl -X POST http://localhost:8082/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 101,
    "eventId": 1,
    "ticketId": 1,
    "price": 5500.00
  }'
```

**Ожидаемый ответ:** `201 Created`
```json
{
  "id": 1,
  "userId": 101,
  "eventId": 1,
  "totalPrice": 5500.00,
  "status": "CREATED",
  "createdAt": "2026-08-27T13:00:00Z"
}
```

---

### Сценарий 3. Проверка пессимистической блокировки (Double-Spending Prevention)

Попробуйте купить тот же билет №1 повторно:
```bash
curl -X POST http://localhost:8082/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 102,
    "eventId": 1,
    "ticketId": 1,
    "price": 5500.00
  }'
```

**Ожидаемый ответ:** `409 Conflict`  
*(Запрос отклонен, так как билет уже заблокирован и недоступен).*

---

### Сценарий 4. Проверка событий в Notification Service и Kafka UI

1. В логах консоли `notification-service` появится сообщение:
   ```text
   Уведомление отправлено пользователю 101: Ваш заказ #1 успешно оформлен на сумму 5500.00!
   ```
2. Откройте в браузере **Kafka UI**: [http://localhost:8080](http://localhost:8080):
   - Перейдите в раздел **Topics** -> **`order-events`** -> вкладка **Messages**.
   - Убедитесь в наличии события с ключом `101` и телом `OrderEvent`.

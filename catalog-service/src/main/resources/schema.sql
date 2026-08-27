-- =========================================================
-- Схема базы данных catalog_db для catalog-service
-- =========================================================

-- Таблица мероприятий
CREATE TABLE IF NOT EXISTS events (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    event_date TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Таблица билетов и посадочных мест
CREATE TABLE IF NOT EXISTS tickets (
    id BIGSERIAL PRIMARY KEY,
    seat_number VARCHAR(128) NOT NULL,
    price NUMERIC(12, 2) NOT NULL,
    is_available BOOLEAN NOT NULL DEFAULT TRUE,
    event_id BIGINT NOT NULL,
    CONSTRAINT fk_tickets_event 
        FOREIGN KEY (event_id) 
        REFERENCES events(id) 
        ON DELETE CASCADE
);

-- Индексы для оптимизации поиска и JOIN
CREATE INDEX IF NOT EXISTS idx_tickets_event_id ON tickets(event_id);
CREATE INDEX IF NOT EXISTS idx_tickets_available ON tickets(is_available);

CREATE TABLE tickets
(
    id              UUID          NOT NULL,
    event_id        UUID          NOT NULL,
    seat_number     VARCHAR(50)   NOT NULL,
    price           NUMERIC(19, 2) NOT NULL,

    status          VARCHAR(20)   NOT NULL DEFAULT 'AVAILABLE',
    reservation_id  UUID,
    reserved_until  TIMESTAMP,

    version         BIGINT        NOT NULL DEFAULT 0,

    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_tickets PRIMARY KEY (id),

    CONSTRAINT fk_tickets_event
        FOREIGN KEY (event_id)
        REFERENCES events (id),

    CONSTRAINT uq_tickets_event_seat
        UNIQUE (event_id, seat_number)
);

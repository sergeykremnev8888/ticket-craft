CREATE TABLE events
(
    id          UUID         NOT NULL,
    title       VARCHAR(255) NOT NULL,
    description TEXT,
    event_date  TIMESTAMPTZ  NOT NULL,
    venue       VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_events PRIMARY KEY (id)
);

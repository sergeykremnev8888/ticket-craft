CREATE TABLE processed_events
(
    message_id   VARCHAR(255) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_processed_events PRIMARY KEY (message_id)
);

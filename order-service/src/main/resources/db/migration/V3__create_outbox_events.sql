CREATE TABLE outbox_events
(
    id              UUID         NOT NULL,
    aggregate_type  VARCHAR(64)  NOT NULL,
    aggregate_id    VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(128) NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at    TIMESTAMPTZ,

    attempts        INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    locked_at       TIMESTAMPTZ,
    locked_by       VARCHAR(128),
    claim_id        UUID,

    CONSTRAINT pk_outbox_events
        PRIMARY KEY (id)
);

CREATE INDEX idx_outbox_events_pending
    ON outbox_events (next_attempt_at, created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_outbox_events_claim_id
    ON outbox_events (claim_id)
    WHERE claim_id IS NOT NULL;

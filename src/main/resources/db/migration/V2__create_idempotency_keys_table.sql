CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(255) NOT NULL,
    booking_id      VARCHAR(10),
    status          VARCHAR(20),
    created_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_idempotency_keys PRIMARY KEY (idempotency_key)
);

-- Supports a future cleanup job deleting old keys (none exists yet - see README known gaps).
CREATE INDEX idx_idempotency_keys_created_at ON idempotency_keys (created_at);

CREATE TABLE webhook_events (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(100) NOT NULL UNIQUE,
    payment_id BIGINT NOT NULL REFERENCES payments (id),
    status_received VARCHAR(20) NOT NULL,
    payload JSONB,
    processed_at TIMESTAMP NOT NULL DEFAULT now(),
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_webhook_events_payment_id ON webhook_events (payment_id);

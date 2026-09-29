CREATE TABLE idempotency_request (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    idempotency_key VARCHAR(128) NOT NULL,
    request_method VARCHAR(16) NOT NULL,
    request_path VARCHAR(512) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PROCESSING','COMPLETED')),
    response_status INTEGER,
    response_content_type VARCHAR(128),
    response_body BYTEA,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '24 hours'),
    UNIQUE (user_id, idempotency_key)
);

CREATE INDEX idx_idempotency_expiry ON idempotency_request(expires_at);


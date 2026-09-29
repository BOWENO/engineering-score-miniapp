CREATE TABLE wechat_binding_ticket (
    id UUID PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    openid VARCHAR(128) NOT NULL,
    unionid VARCHAR(128),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_wechat_binding_ticket_expiry ON wechat_binding_ticket(expires_at);

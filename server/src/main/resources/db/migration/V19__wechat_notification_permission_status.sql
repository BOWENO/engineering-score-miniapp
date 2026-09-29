ALTER TABLE wechat_subscription_credit
    ADD COLUMN permission_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUESTED',
    ADD COLUMN last_requested_at TIMESTAMPTZ,
    ADD COLUMN last_response_at TIMESTAMPTZ,
    ADD COLUMN last_error_code VARCHAR(64);

ALTER TABLE wechat_subscription_credit
    ADD CONSTRAINT ck_wechat_subscription_permission_status
        CHECK (permission_status IN ('NOT_REQUESTED','ACCEPTED','REJECTED','BANNED','EXHAUSTED'));

UPDATE wechat_subscription_credit
SET permission_status = CASE WHEN credits > 0 THEN 'ACCEPTED' ELSE 'EXHAUSTED' END,
    last_response_at = updated_at
WHERE permission_status = 'NOT_REQUESTED';

CREATE INDEX idx_wechat_subscription_permission
    ON wechat_subscription_credit(template_id,permission_status,updated_at DESC);

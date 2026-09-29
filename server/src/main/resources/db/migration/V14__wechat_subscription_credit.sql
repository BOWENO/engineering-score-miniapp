CREATE TABLE wechat_subscription_credit (
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    template_id VARCHAR(128) NOT NULL,
    credits INTEGER NOT NULL DEFAULT 0 CHECK (credits >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id,template_id)
);

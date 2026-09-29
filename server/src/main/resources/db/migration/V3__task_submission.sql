ALTER TABLE score_rule ADD COLUMN title VARCHAR(128);
UPDATE score_rule SET title=code WHERE title IS NULL;
ALTER TABLE score_rule ALTER COLUMN title SET NOT NULL;

CREATE TABLE task_submission (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES daily_task(id),
    user_id UUID NOT NULL REFERENCES app_user(id),
    submitted_value VARCHAR(512),
    description TEXT,
    idempotency_key VARCHAR(128) NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, idempotency_key),
    UNIQUE (task_id)
);

CREATE TABLE validation_result (
    id UUID PRIMARY KEY,
    submission_id UUID NOT NULL REFERENCES task_submission(id),
    valid BOOLEAN NOT NULL,
    code VARCHAR(64) NOT NULL,
    message VARCHAR(512) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_daily_task_user_date ON daily_task(user_id, biz_date);
CREATE INDEX idx_task_submission_user ON task_submission(user_id, submitted_at);

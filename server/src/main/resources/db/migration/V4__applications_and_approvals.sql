ALTER TABLE score_application ADD COLUMN rule_version_id UUID REFERENCES rule_version(id);
ALTER TABLE score_application ADD COLUMN submitted_at TIMESTAMPTZ;
ALTER TABLE score_application ADD COLUMN decided_at TIMESTAMPTZ;
ALTER TABLE score_application ADD COLUMN decision_reason_code VARCHAR(64);
ALTER TABLE score_application ADD COLUMN decision_comment VARCHAR(1000);

CREATE TABLE application_attachment (
    application_id UUID NOT NULL REFERENCES score_application(id) ON DELETE CASCADE,
    attachment_id UUID NOT NULL REFERENCES attachment(id),
    PRIMARY KEY (application_id, attachment_id)
);

CREATE TABLE approval_action (
    id UUID PRIMARY KEY,
    approval_id UUID NOT NULL REFERENCES approval_instance(id),
    actor_id UUID NOT NULL REFERENCES app_user(id),
    action VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64),
    comment VARCHAR(1000),
    from_status VARCHAR(32) NOT NULL,
    to_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_application_status ON score_application(status, created_at);
CREATE INDEX idx_approval_status ON approval_instance(status, created_at);

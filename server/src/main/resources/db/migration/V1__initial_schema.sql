CREATE TABLE org_unit (
    id UUID PRIMARY KEY,
    parent_id UUID REFERENCES org_unit(id),
    type VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    openid VARCHAR(128) UNIQUE,
    unionid VARCHAR(128),
    employee_no VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(128) NOT NULL,
    org_unit_id UUID NOT NULL REFERENCES org_unit(id),
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE role_binding (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    role_code VARCHAR(32) NOT NULL,
    scope_id UUID REFERENCES org_unit(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, role_code, scope_id)
);

CREATE TABLE rule_version (
    id UUID PRIMARY KEY,
    version VARCHAR(32) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL,
    effective_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE score_rule (
    id UUID PRIMARY KEY,
    rule_version_id UUID NOT NULL REFERENCES rule_version(id),
    code VARCHAR(64) NOT NULL,
    type VARCHAR(32) NOT NULL,
    score NUMERIC(12,2),
    cap_policy JSONB NOT NULL DEFAULT '{}'::jsonb,
    evidence_schema JSONB NOT NULL DEFAULT '{}'::jsonb,
    approval_flow JSONB NOT NULL DEFAULT '{}'::jsonb,
    effective_from DATE NOT NULL,
    effective_to DATE,
    UNIQUE (rule_version_id, code)
);

CREATE TABLE daily_task (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    biz_date DATE NOT NULL,
    rule_code VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    deadline_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (user_id, biz_date, rule_code)
);

CREATE TABLE score_application (
    id UUID PRIMARY KEY,
    applicant_id UUID NOT NULL REFERENCES app_user(id),
    rule_code VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    description TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (applicant_id, idempotency_key)
);

CREATE TABLE approval_instance (
    id UUID PRIMARY KEY,
    biz_type VARCHAR(32) NOT NULL,
    biz_id UUID NOT NULL,
    current_node VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE score_event (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    rule_code VARCHAR(64) NOT NULL,
    biz_date DATE NOT NULL,
    original_score NUMERIC(12,2) NOT NULL,
    actual_score NUMERIC(12,2) NOT NULL,
    cap_reason VARCHAR(256),
    source VARCHAR(32) NOT NULL,
    source_id UUID NOT NULL,
    original_event_id UUID REFERENCES score_event(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (source, source_id, rule_code)
);

CREATE INDEX idx_score_event_user_date ON score_event(user_id, biz_date);

CREATE TABLE score_summary (
    user_id UUID NOT NULL REFERENCES app_user(id),
    period CHAR(7) NOT NULL,
    base NUMERIC(12,2) NOT NULL DEFAULT 0,
    bonus NUMERIC(12,2) NOT NULL DEFAULT 0,
    penalty NUMERIC(12,2) NOT NULL DEFAULT 0,
    total NUMERIC(12,2) NOT NULL DEFAULT 0,
    rebuilt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, period)
);

CREATE TABLE grade_snapshot (
    id UUID PRIMARY KEY,
    period CHAR(7) NOT NULL,
    user_id UUID NOT NULL REFERENCES app_user(id),
    grade VARCHAR(8) NOT NULL,
    rank_no INTEGER NOT NULL,
    settlement_version VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (period, user_id, settlement_version)
);

CREATE TABLE appeal (
    id UUID PRIMARY KEY,
    result_id UUID NOT NULL REFERENCES grade_snapshot(id),
    applicant_id UUID NOT NULL REFERENCES app_user(id),
    reason_code VARCHAR(64) NOT NULL,
    description TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE attachment (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user(id),
    object_key VARCHAR(512) NOT NULL UNIQUE,
    content_hash VARCHAR(128) NOT NULL,
    mime_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE operation_log (
    id UUID PRIMARY KEY,
    actor_id UUID,
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id UUID,
    before_data JSONB,
    after_data JSONB,
    request_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_operation_log_target ON operation_log(target_type, target_id);
CREATE INDEX idx_operation_log_request ON operation_log(request_id);

CREATE OR REPLACE FUNCTION reject_score_event_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'score_event is append-only; create a reversal event instead';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER score_event_no_update
BEFORE UPDATE OR DELETE ON score_event
FOR EACH ROW EXECUTE FUNCTION reject_score_event_mutation();

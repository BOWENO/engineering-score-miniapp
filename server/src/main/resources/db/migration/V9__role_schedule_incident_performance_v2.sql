ALTER TABLE app_user ADD COLUMN is_administrator BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE app_user ADD COLUMN is_review_account BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE app_user ADD COLUMN password_change_required BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE app_user ADD COLUMN failed_login_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE app_user ADD COLUMN locked_until TIMESTAMPTZ;
ALTER TABLE app_user ADD COLUMN last_login_at TIMESTAMPTZ;

UPDATE app_user SET is_administrator=TRUE WHERE employee_no='11275391';
UPDATE app_user SET is_review_account=TRUE WHERE lower(employee_no)='wxreview';
DELETE FROM role_binding WHERE role_code IN ('ENGINEER','CLERK','SYSTEM_ADMIN');
DELETE FROM admin_credential WHERE username='admin';

ALTER TABLE d_grade_nomination ALTER COLUMN evidence_attachment_id DROP NOT NULL;
ALTER TABLE d_grade_nomination ADD COLUMN confirmed_at TIMESTAMPTZ;
ALTER TABLE d_grade_nomination ADD COLUMN appeal_deadline_at TIMESTAMPTZ;
ALTER TABLE d_grade_confirmation ADD COLUMN decision VARCHAR(16) NOT NULL DEFAULT 'APPROVE'
    CHECK (decision IN ('APPROVE','REJECT'));

CREATE TABLE d_grade_appeal (
    id UUID PRIMARY KEY,
    nomination_id UUID NOT NULL REFERENCES d_grade_nomination(id),
    applicant_id UUID NOT NULL REFERENCES app_user(id),
    description VARCHAR(2000) NOT NULL,
    status VARCHAR(32) NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at TIMESTAMPTZ,
    UNIQUE (nomination_id, applicant_id)
);

CREATE TABLE d_grade_appeal_vote (
    id UUID PRIMARY KEY,
    appeal_id UUID NOT NULL REFERENCES d_grade_appeal(id),
    supervisor_id UUID NOT NULL REFERENCES app_user(id),
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('APPROVE','REJECT')),
    comment VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (appeal_id, supervisor_id)
);

CREATE TABLE shift_definition (
    id UUID PRIMARY KEY,
    code VARCHAR(16) NOT NULL,
    name VARCHAR(64) NOT NULL,
    starts_at TIME NOT NULL,
    ends_at TIME NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','DISABLED')),
    created_by UUID REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (code, effective_from),
    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

INSERT INTO shift_definition(id,code,name,starts_at,ends_at,effective_from,status)
VALUES ('00000000-0000-0000-0000-000000000101','DAY','白班','08:30','20:30','2020-01-01','ACTIVE'),
       ('00000000-0000-0000-0000-000000000102','NIGHT','夜班','20:30','08:30','2020-01-01','ACTIVE');

CREATE TABLE work_calendar (
    calendar_date DATE PRIMARY KEY,
    day_type VARCHAR(16) NOT NULL CHECK (day_type IN ('HOLIDAY','WORKDAY')),
    name VARCHAR(128) NOT NULL,
    source VARCHAR(32) NOT NULL DEFAULT 'ADMIN',
    updated_by UUID REFERENCES app_user(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE production_line (
    id UUID PRIMARY KEY,
    org_unit_id UUID NOT NULL REFERENCES org_unit(id),
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','DISABLED')),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (org_unit_id, code)
);

CREATE TABLE station (
    id UUID PRIMARY KEY,
    line_id UUID NOT NULL REFERENCES production_line(id),
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','DISABLED')),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (line_id, code)
);

ALTER TABLE equipment ADD COLUMN line_id UUID REFERENCES production_line(id);
ALTER TABLE equipment ADD COLUMN station_id UUID REFERENCES station(id);
ALTER TABLE equipment ADD COLUMN disabled_at TIMESTAMPTZ;

CREATE TABLE schedule_assignment (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    team_id UUID NOT NULL REFERENCES org_unit(id),
    business_date DATE NOT NULL,
    shift_id UUID NOT NULL REFERENCES shift_definition(id),
    shift_code VARCHAR(16) NOT NULL,
    shift_starts_at TIMESTAMPTZ NOT NULL,
    shift_ends_at TIMESTAMPTZ NOT NULL,
    line_id UUID NOT NULL REFERENCES production_line(id),
    station_id UUID NOT NULL REFERENCES station(id),
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','PUBLISHED','CANCELLED')),
    created_by UUID NOT NULL REFERENCES app_user(id),
    published_by UUID REFERENCES app_user(id),
    published_at TIMESTAMPTZ,
    acknowledged_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, business_date, shift_code, line_id, station_id)
);

CREATE INDEX idx_schedule_responsibility ON schedule_assignment(business_date, shift_code, station_id, status);
CREATE INDEX idx_schedule_user ON schedule_assignment(user_id, shift_starts_at, status);

CREATE TABLE schedule_revision (
    id UUID PRIMARY KEY,
    assignment_id UUID NOT NULL REFERENCES schedule_assignment(id),
    actor_id UUID NOT NULL REFERENCES app_user(id),
    action VARCHAR(32) NOT NULL,
    reason VARCHAR(1000),
    before_data JSONB,
    after_data JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE shift_score (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    business_date DATE NOT NULL,
    shift_code VARCHAR(16) NOT NULL,
    shift_ends_at TIMESTAMPTZ NOT NULL,
    base_score INTEGER NOT NULL DEFAULT 10 CHECK (base_score BETWEEN 0 AND 10),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','POSTED','CANCELLED')),
    posted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, business_date, shift_code)
);

CREATE TABLE notification (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    type VARCHAR(64) NOT NULL,
    title VARCHAR(128) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    source_type VARCHAR(64),
    source_id UUID,
    requires_acknowledgement BOOLEAN NOT NULL DEFAULT FALSE,
    read_at TIMESTAMPTZ,
    acknowledged_at TIMESTAMPTZ,
    wechat_delivery_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUESTED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_notification_user_unread ON notification(user_id, created_at DESC) WHERE read_at IS NULL;

CREATE TABLE performance_case (
    id UUID PRIMARY KEY,
    case_type VARCHAR(32) NOT NULL CHECK (case_type IN ('BONUS','BASE_DEDUCTION','SPECIAL_DEDUCTION','ADMONITION')),
    target_user_id UUID NOT NULL REFERENCES app_user(id),
    initiated_by UUID NOT NULL REFERENCES app_user(id),
    occurred_at TIMESTAMPTZ NOT NULL,
    description VARCHAR(2000) NOT NULL,
    rule_code VARCHAR(64),
    suggested_score INTEGER,
    approved_score INTEGER,
    effective_score INTEGER,
    status VARCHAR(32) NOT NULL,
    current_stage VARCHAR(32),
    direct_by_supervisor BOOLEAN NOT NULL DEFAULT FALSE,
    original_supervisor_id UUID REFERENCES app_user(id),
    due_at TIMESTAMPTZ,
    appeal_deadline_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_performance_case_target ON performance_case(target_user_id, occurred_at DESC);
CREATE INDEX idx_performance_case_pending ON performance_case(status, current_stage, due_at);

CREATE TABLE performance_case_attachment (
    case_id UUID NOT NULL REFERENCES performance_case(id),
    attachment_id UUID NOT NULL REFERENCES attachment(id),
    PRIMARY KEY (case_id, attachment_id)
);

CREATE TABLE performance_case_action (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES performance_case(id),
    actor_id UUID NOT NULL REFERENCES app_user(id),
    action VARCHAR(32) NOT NULL,
    stage VARCHAR(32),
    comment VARCHAR(1000),
    score INTEGER,
    from_status VARCHAR(32) NOT NULL,
    to_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE performance_appeal (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES performance_case(id),
    applicant_id UUID NOT NULL REFERENCES app_user(id),
    description VARCHAR(2000) NOT NULL,
    status VARCHAR(32) NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at TIMESTAMPTZ,
    UNIQUE (case_id, applicant_id)
);

CREATE TABLE performance_appeal_vote (
    id UUID PRIMARY KEY,
    appeal_id UUID NOT NULL REFERENCES performance_appeal(id),
    supervisor_id UUID NOT NULL REFERENCES app_user(id),
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('APPROVE','REJECT')),
    comment VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (appeal_id, supervisor_id)
);

CREATE TABLE equipment_incident (
    id UUID PRIMARY KEY,
    incident_no VARCHAR(32) NOT NULL UNIQUE,
    occurred_at TIMESTAMPTZ NOT NULL,
    business_date DATE NOT NULL,
    shift_code VARCHAR(16) NOT NULL,
    team_id UUID NOT NULL REFERENCES org_unit(id),
    line_id UUID NOT NULL REFERENCES production_line(id),
    station_id UUID NOT NULL REFERENCES station(id),
    status VARCHAR(32) NOT NULL CHECK (status IN ('WAITING_STATEMENTS','UNDER_REVIEW','RETURNED','ARCHIVED','VOID')),
    created_by UUID NOT NULL REFERENCES app_user(id),
    reviewer_id UUID REFERENCES app_user(id),
    review_comment VARCHAR(1000),
    archived_at TIMESTAMPTZ,
    voided_by UUID REFERENCES app_user(id),
    void_reason VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_incident_archive ON equipment_incident(occurred_at DESC, status);
CREATE INDEX idx_incident_stats ON equipment_incident(line_id, station_id, occurred_at DESC);

CREATE TABLE incident_equipment (
    incident_id UUID NOT NULL REFERENCES equipment_incident(id),
    equipment_id UUID NOT NULL REFERENCES equipment(id),
    PRIMARY KEY (incident_id, equipment_id)
);

CREATE TABLE incident_statement (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES equipment_incident(id),
    responsible_user_id UUID NOT NULL REFERENCES app_user(id),
    phenomenon VARCHAR(2000),
    handling_method VARCHAR(2000),
    root_cause VARCHAR(2000),
    long_term_action VARCHAR(2000),
    status VARCHAR(32) NOT NULL CHECK (status IN ('PENDING','SUBMITTED','APPROVED','RETURNED','OVERDUE')),
    due_at TIMESTAMPTZ NOT NULL,
    resubmit_due_at TIMESTAMPTZ,
    submitted_at TIMESTAMPTZ,
    reviewed_at TIMESTAMPTZ,
    reviewer_id UUID REFERENCES app_user(id),
    review_comment VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (incident_id, responsible_user_id)
);

CREATE INDEX idx_incident_statement_due ON incident_statement(status, due_at);

CREATE TABLE incident_note (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES equipment_incident(id),
    actor_id UUID NOT NULL REFERENCES app_user(id),
    content VARCHAR(2000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE settlement_candidate ADD COLUMN shift_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE settlement_candidate ADD COLUMN average_score NUMERIC(14,6) NOT NULL DEFAULT 0;
ALTER TABLE grade_snapshot ADD COLUMN shift_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE grade_snapshot ADD COLUMN average_score NUMERIC(14,6) NOT NULL DEFAULT 0;

CREATE TABLE equipment (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    category VARCHAR(64) NOT NULL,
    org_unit_id UUID NOT NULL REFERENCES org_unit(id),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','DISABLED')),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_equipment_org_status ON equipment(org_unit_id, status);
CREATE INDEX idx_equipment_category ON equipment(category, name);

CREATE TABLE device_exception (
    id UUID PRIMARY KEY,
    equipment_id UUID NOT NULL REFERENCES equipment(id),
    reporter_id UUID NOT NULL REFERENCES app_user(id),
    occurred_on DATE NOT NULL,
    phenomenon VARCHAR(2000) NOT NULL,
    handling_method VARCHAR(2000) NOT NULL,
    root_cause VARCHAR(2000) NOT NULL,
    long_term_action VARCHAR(2000),
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','SUBMITTED','ARCHIVED','RETURNED')),
    idempotency_key VARCHAR(128) NOT NULL,
    reviewer_id UUID REFERENCES app_user(id),
    review_comment VARCHAR(1000),
    submitted_at TIMESTAMPTZ,
    reviewed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (reporter_id, idempotency_key)
);

CREATE INDEX idx_device_exception_reporter ON device_exception(reporter_id, created_at DESC);
CREATE INDEX idx_device_exception_review ON device_exception(status, submitted_at);
CREATE INDEX idx_device_exception_archive ON device_exception(equipment_id, occurred_on DESC) WHERE status='ARCHIVED';

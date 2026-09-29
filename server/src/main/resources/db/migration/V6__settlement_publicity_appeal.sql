CREATE TABLE d_grade_nomination (
    id UUID PRIMARY KEY,
    period CHAR(7) NOT NULL,
    user_id UUID NOT NULL REFERENCES app_user(id),
    org_unit_id UUID NOT NULL REFERENCES org_unit(id),
    reason_code VARCHAR(64) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    evidence_attachment_id UUID NOT NULL REFERENCES attachment(id),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    nominated_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (period, user_id)
);

CREATE TABLE d_grade_confirmation (
    id UUID PRIMARY KEY,
    nomination_id UUID NOT NULL REFERENCES d_grade_nomination(id),
    reviewer_id UUID NOT NULL REFERENCES app_user(id),
    comment VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (nomination_id, reviewer_id)
);

CREATE TABLE settlement_run (
    id UUID PRIMARY KEY,
    period CHAR(7) NOT NULL,
    org_unit_id UUID NOT NULL REFERENCES org_unit(id),
    settlement_version VARCHAR(32) NOT NULL,
    rule_version_id UUID NOT NULL REFERENCES rule_version(id),
    status VARCHAR(32) NOT NULL,
    created_by UUID NOT NULL REFERENCES app_user(id),
    published_by UUID REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    UNIQUE (period, org_unit_id, settlement_version)
);

CREATE TABLE settlement_candidate (
    run_id UUID NOT NULL REFERENCES settlement_run(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_user(id),
    total NUMERIC(12,2) NOT NULL,
    base NUMERIC(12,2) NOT NULL,
    bonus NUMERIC(12,2) NOT NULL,
    penalty NUMERIC(12,2) NOT NULL,
    proposed_grade VARCHAR(8) NOT NULL,
    rank_no INTEGER NOT NULL,
    manual_required BOOLEAN NOT NULL DEFAULT FALSE,
    manual_grade VARCHAR(8),
    manual_reason VARCHAR(1000),
    decided_by UUID REFERENCES app_user(id),
    PRIMARY KEY (run_id, user_id)
);

ALTER TABLE grade_snapshot ADD COLUMN settlement_run_id UUID REFERENCES settlement_run(id);
ALTER TABLE grade_snapshot ADD COLUMN total NUMERIC(12,2) NOT NULL DEFAULT 0;
ALTER TABLE grade_snapshot ADD COLUMN base NUMERIC(12,2) NOT NULL DEFAULT 0;
ALTER TABLE grade_snapshot ADD COLUMN bonus NUMERIC(12,2) NOT NULL DEFAULT 0;
ALTER TABLE grade_snapshot ADD COLUMN penalty NUMERIC(12,2) NOT NULL DEFAULT 0;
ALTER TABLE grade_snapshot ADD COLUMN published_at TIMESTAMPTZ;

ALTER TABLE appeal ADD COLUMN decision VARCHAR(32);
ALTER TABLE appeal ADD COLUMN decision_comment VARCHAR(1000);
ALTER TABLE appeal ADD COLUMN decided_by UUID REFERENCES app_user(id);
ALTER TABLE appeal ADD COLUMN decided_at TIMESTAMPTZ;
ALTER TABLE appeal ADD CONSTRAINT uq_appeal_result_applicant UNIQUE (result_id, applicant_id);

CREATE INDEX idx_d_nomination_period_org ON d_grade_nomination(period, org_unit_id, status);
CREATE INDEX idx_settlement_period_org ON settlement_run(period, org_unit_id, status);
CREATE INDEX idx_grade_snapshot_public ON grade_snapshot(period, status, rank_no);
CREATE INDEX idx_appeal_status ON appeal(status, created_at);

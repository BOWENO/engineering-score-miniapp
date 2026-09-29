CREATE TABLE settlement_confirmation (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES settlement_run(id) ON DELETE CASCADE,
    supervisor_id UUID NOT NULL REFERENCES app_user(id),
    comment VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (run_id,supervisor_id)
);

CREATE TABLE settlement_boundary_vote (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    user_id UUID NOT NULL,
    supervisor_id UUID NOT NULL REFERENCES app_user(id),
    grade VARCHAR(8) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (run_id,user_id) REFERENCES settlement_candidate(run_id,user_id) ON DELETE CASCADE,
    UNIQUE (run_id,user_id,supervisor_id)
);

CREATE TABLE settlement_correction (
    id UUID PRIMARY KEY,
    settlement_run_id UUID NOT NULL REFERENCES settlement_run(id),
    user_id UUID NOT NULL REFERENCES app_user(id),
    reason VARCHAR(2000) NOT NULL,
    requested_by UUID NOT NULL REFERENCES app_user(id),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at TIMESTAMPTZ
);

CREATE TABLE settlement_correction_vote (
    id UUID PRIMARY KEY,
    correction_id UUID NOT NULL REFERENCES settlement_correction(id) ON DELETE CASCADE,
    supervisor_id UUID NOT NULL REFERENCES app_user(id),
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('APPROVE','REJECT')),
    comment VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (correction_id,supervisor_id)
);

CREATE INDEX idx_settlement_confirmation_run ON settlement_confirmation(run_id);
CREATE INDEX idx_settlement_correction_status ON settlement_correction(status,created_at);

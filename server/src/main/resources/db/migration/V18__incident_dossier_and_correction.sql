ALTER TABLE equipment_incident DROP CONSTRAINT IF EXISTS equipment_incident_status_check;
ALTER TABLE equipment_incident ADD CONSTRAINT equipment_incident_status_check CHECK (status IN (
    'WAITING_STATEMENTS','UNDER_REVIEW','RETURNED','INVESTIGATING','RESPONSIBILITY_PENDING',
    'CORRECTING','PENDING_ACCEPTANCE','ARCHIVED','VOID'
));
ALTER TABLE equipment_incident ADD COLUMN severity VARCHAR(16) NOT NULL DEFAULT 'GENERAL'
    CHECK (severity IN ('GENERAL','IMPORTANT','MAJOR'));
ALTER TABLE equipment_incident ADD COLUMN category_code VARCHAR(64) NOT NULL DEFAULT 'EQUIPMENT';
ALTER TABLE equipment_incident ADD COLUMN impact_level VARCHAR(16) NOT NULL DEFAULT 'LOW'
    CHECK (impact_level IN ('LOW','MEDIUM','HIGH'));
ALTER TABLE equipment_incident ADD COLUMN downtime_minutes INTEGER NOT NULL DEFAULT 0 CHECK (downtime_minutes >= 0);
ALTER TABLE equipment_incident ADD COLUMN impact_description VARCHAR(2000);
ALTER TABLE equipment_incident ADD COLUMN performance_required BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE equipment_incident ADD COLUMN archive_check_result JSONB;
ALTER TABLE equipment_incident ADD COLUMN reopened_at TIMESTAMPTZ;
ALTER TABLE equipment_incident ADD COLUMN reopened_by UUID REFERENCES app_user(id);
ALTER TABLE equipment_incident ADD COLUMN reopen_reason VARCHAR(1000);
ALTER TABLE equipment_incident ADD COLUMN archive_version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE performance_case ADD COLUMN incident_id UUID REFERENCES equipment_incident(id);
CREATE INDEX idx_performance_case_incident ON performance_case(incident_id,status);

CREATE TABLE incident_evidence (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES equipment_incident(id),
    attachment_id UUID NOT NULL REFERENCES attachment(id),
    evidence_type VARCHAR(32) NOT NULL,
    description VARCHAR(1000),
    uploaded_by UUID NOT NULL REFERENCES app_user(id),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','VOID')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    voided_at TIMESTAMPTZ,
    voided_by UUID REFERENCES app_user(id),
    void_reason VARCHAR(1000),
    UNIQUE (incident_id, attachment_id)
);

CREATE TABLE incident_investigation (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL UNIQUE REFERENCES equipment_incident(id),
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('SIMPLE','FULL')),
    lead_user_id UUID NOT NULL REFERENCES app_user(id),
    direct_cause VARCHAR(2000) NOT NULL,
    root_cause VARCHAR(2000) NOT NULL,
    root_cause_category VARCHAR(64) NOT NULL,
    five_whys JSONB,
    conclusion VARCHAR(2000) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','SUBMITTED','CONFIRMED','RETURNED')),
    confirmed_by UUID REFERENCES app_user(id),
    confirmed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE incident_responsibility (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES equipment_incident(id),
    responsible_user_id UUID REFERENCES app_user(id),
    responsible_org_id UUID REFERENCES org_unit(id),
    responsibility_type VARCHAR(32) NOT NULL,
    responsibility_percent INTEGER CHECK (responsibility_percent BETWEEN 0 AND 100),
    basis VARCHAR(2000) NOT NULL,
    proposed_action VARCHAR(1000),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PROPOSED','CONFIRMED','APPEALED','VOID')),
    created_by UUID NOT NULL REFERENCES app_user(id),
    confirmed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (responsible_user_id IS NOT NULL OR responsible_org_id IS NOT NULL)
);

CREATE TABLE incident_corrective_action (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES equipment_incident(id),
    action_type VARCHAR(32) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    owner_id UUID NOT NULL REFERENCES app_user(id),
    due_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('PENDING','IN_PROGRESS','PENDING_ACCEPTANCE','ACCEPTED','RETURNED','CANCELLED')),
    completion_note VARCHAR(2000),
    completed_at TIMESTAMPTZ,
    accepted_by UUID REFERENCES app_user(id),
    acceptance_result VARCHAR(16) CHECK (acceptance_result IN ('ACCEPTED','RETURNED','CANCELLED')),
    acceptance_comment VARCHAR(1000),
    accepted_at TIMESTAMPTZ,
    reminder_sent_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_incident_action_owner ON incident_corrective_action(owner_id,status,due_at);
CREATE INDEX idx_incident_action_incident ON incident_corrective_action(incident_id,status);

CREATE TABLE incident_archive_event (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES equipment_incident(id),
    event_type VARCHAR(64) NOT NULL,
    actor_id UUID NOT NULL REFERENCES app_user(id),
    actor_roles VARCHAR(512),
    actor_org_id UUID,
    object_type VARCHAR(64),
    object_id UUID,
    detail JSONB NOT NULL DEFAULT '{}'::jsonb,
    request_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_incident_event_timeline ON incident_archive_event(incident_id,created_at,id);

CREATE TABLE incident_relation (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES equipment_incident(id),
    related_incident_id UUID NOT NULL REFERENCES equipment_incident(id),
    relation_type VARCHAR(32) NOT NULL CHECK (relation_type IN ('SIMILAR','REPEAT','UPSTREAM','DOWNSTREAM')),
    confirmed_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (incident_id, related_incident_id, relation_type),
    CHECK (incident_id <> related_incident_id)
);

INSERT INTO incident_archive_event(id,incident_id,event_type,actor_id,actor_roles,actor_org_id,detail,created_at)
SELECT gen_random_uuid(),i.id,'LEGACY_IMPORTED',i.created_by,'',i.team_id,
       jsonb_build_object('legacyStatus',i.status),i.created_at
FROM equipment_incident i;

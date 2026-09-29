ALTER TABLE rule_version ADD COLUMN created_by UUID REFERENCES app_user(id);
ALTER TABLE rule_version ADD COLUMN business_approved_by UUID REFERENCES app_user(id);
ALTER TABLE rule_version ADD COLUMN business_approved_at TIMESTAMPTZ;
ALTER TABLE rule_version ADD COLUMN published_by UUID REFERENCES app_user(id);
ALTER TABLE rule_version ADD COLUMN published_at TIMESTAMPTZ;

ALTER TABLE daily_task ADD COLUMN rule_version_id UUID REFERENCES rule_version(id);
UPDATE daily_task t SET rule_version_id=(
    SELECT r.rule_version_id FROM score_rule r
    WHERE r.code=t.rule_code AND t.biz_date BETWEEN r.effective_from AND COALESCE(r.effective_to,t.biz_date)
    ORDER BY r.effective_from DESC LIMIT 1
) WHERE t.rule_version_id IS NULL;

CREATE INDEX idx_user_org_status ON app_user(org_unit_id, status);
CREATE INDEX idx_role_scope ON role_binding(scope_id, role_code);
CREATE INDEX idx_rule_version_status ON rule_version(status, created_at);
CREATE INDEX idx_daily_task_rule_version ON daily_task(rule_version_id, rule_code);

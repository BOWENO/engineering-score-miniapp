CREATE TABLE settlement_approver (
    run_id UUID NOT NULL REFERENCES settlement_run(id),
    user_id UUID NOT NULL REFERENCES app_user(id),
    PRIMARY KEY (run_id,user_id)
);
CREATE TABLE settlement_correction_approver (
    correction_id UUID NOT NULL REFERENCES settlement_correction(id),
    user_id UUID NOT NULL REFERENCES app_user(id),
    PRIMARY KEY (correction_id,user_id)
);

-- Existing batches receive a snapshot of currently authorized formal supervisors.
WITH RECURSIVE scope(user_id,org_id) AS (
    SELECT rb.user_id,o.id FROM role_binding rb JOIN app_user u ON u.id=rb.user_id
    JOIN org_unit o ON o.id=rb.scope_id
    WHERE rb.role_code='SUPERVISOR' AND u.status='ACTIVE' AND NOT u.is_review_account AND NOT o.is_review_data
    UNION
    SELECT s.user_id,o.id FROM scope s JOIN org_unit o ON o.parent_id=s.org_id WHERE NOT o.is_review_data
)
INSERT INTO settlement_approver(run_id,user_id)
SELECT DISTINCT r.id,s.user_id FROM settlement_run r JOIN scope s ON s.org_id=r.org_unit_id;

INSERT INTO settlement_correction_approver(correction_id,user_id)
SELECT c.id,a.user_id FROM settlement_correction c JOIN settlement_approver a ON a.run_id=c.settlement_run_id;

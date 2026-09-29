ALTER TABLE settlement_correction ADD COLUMN adjustment_score INTEGER NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX uq_settlement_correction_pending_user
    ON settlement_correction(settlement_run_id,user_id)
    WHERE status='PENDING';

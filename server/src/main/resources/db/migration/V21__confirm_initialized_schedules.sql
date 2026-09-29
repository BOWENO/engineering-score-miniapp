-- Older demo initialization ran after V20 and could create unconfirmed rows.
UPDATE schedule_assignment
SET acknowledged_at = COALESCE(published_at, created_at, CURRENT_TIMESTAMP),
    version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE status = 'PUBLISHED' AND acknowledged_at IS NULL;

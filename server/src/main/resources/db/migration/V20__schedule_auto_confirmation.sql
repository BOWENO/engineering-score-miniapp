-- Publication is sufficient for a schedule to take effect. This compatibility
-- timestamp does not claim that a technician has read the notification.
UPDATE schedule_assignment
SET acknowledged_at = COALESCE(published_at, created_at),
    version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE status = 'PUBLISHED' AND acknowledged_at IS NULL;

-- Preserve actual read timestamps and delivery status. Do not resend messages.
UPDATE notification
SET requires_acknowledgement = FALSE,
    title = CASE WHEN type = 'SCHEDULE_PUBLISHED' THEN '新排班已发布' ELSE title END,
    content = REPLACE(content, '请确认。', '请查看，无需确认。')
WHERE source_type = 'SCHEDULE_ASSIGNMENT';

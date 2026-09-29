DELETE FROM access_token
WHERE user_id IN (SELECT id FROM app_user WHERE upper(employee_no)='ADMIN001');

DELETE FROM role_binding
WHERE user_id IN (SELECT id FROM app_user WHERE upper(employee_no)='ADMIN001');

DELETE FROM admin_credential
WHERE user_id IN (SELECT id FROM app_user WHERE upper(employee_no)='ADMIN001')
   OR lower(username)='admin';

UPDATE app_user
SET status='DISABLED',
    failed_login_count=0,
    locked_until=NULL,
    is_administrator=FALSE,
    password_change_required=TRUE,
    updated_at=CURRENT_TIMESTAMP
WHERE upper(employee_no)='ADMIN001';

UPDATE app_user
SET is_review_account=TRUE
WHERE lower(employee_no)='wxreview';

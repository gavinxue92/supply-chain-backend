INSERT INTO admin_users (
    username,
    password_hash,
    role,
    is_active,
    must_change_password,
    failed_login_attempts,
    is_deleted
)
VALUES (
    'root-admin',
    '$2a$10$TyVmLftrU39bza7DQEotIOkDIUGJI9uPHRcqxDQxKE/ZUiB0mRxN.',
    'ADMIN',
    TRUE,
    FALSE,
    0,
    FALSE
);

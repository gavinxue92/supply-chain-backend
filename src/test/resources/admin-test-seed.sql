INSERT INTO admin_users (
    username,
    nickname,
    password_hash,
    role,
    is_active,
    must_change_password,
    failed_login_attempts,
    is_deleted
)
VALUES (
    'root-admin',
    'Root Admin',
    '$2a$10$TyVmLftrU39bza7DQEotIOkDIUGJI9uPHRcqxDQxKE/ZUiB0mRxN.',
    'ADMIN',
    TRUE,
    FALSE,
    0,
    FALSE
);

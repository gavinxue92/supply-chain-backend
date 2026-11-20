-- 仅限本地开发：先执行 scripts/schema.sql 创建数据库表，再执行本脚本。
-- 执行方式：mysql -h localhost -P 3307 -u supply_chain -p supply_chain < scripts/init-local-admin.sql
-- 使用 admin / Admin123! 登录后，立即修改临时密码。
-- 禁止在本地开发环境之外使用此共享凭据。
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
SELECT
    'admin',
    '本地管理员',
    '$2a$10$U9AhzcD2qEavlLPExOJrJ.9rmbjP8K/gYTwUQOILVuseqtg69pmfa',
    'ADMIN',
    TRUE,
    TRUE,
    0,
    FALSE
WHERE NOT EXISTS (
    SELECT 1
    FROM admin_users
    WHERE username = 'admin'
);

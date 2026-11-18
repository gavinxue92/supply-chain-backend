-- Run once with a MySQL account that has CREATE DATABASE permission.
-- Then run: mysql -h localhost -P 3306 -u root -p supply_chain < scripts/schema.sql
CREATE DATABASE IF NOT EXISTS `supply_chain`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

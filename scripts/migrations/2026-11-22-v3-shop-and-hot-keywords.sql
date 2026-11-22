-- v3 增量迁移：店铺信息 + 搜索热词
-- 适用：已上线运行、有真实数据的库（不能用 schema.sql 重建）
-- 特性：可重复执行；只新增表与数据，不修改、不删除任何既有结构
-- 执行：docker compose -f docker-compose.lite.yml exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" supply_chain' < scripts/migrations/2026-11-22-v3-shop-and-hot-keywords.sql

CREATE TABLE IF NOT EXISTS search_hot_keywords (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '搜索热词主键；按 sort_order 升序展示',
    keyword VARCHAR(120) NOT NULL COMMENT '热词文本；由运营在管理端维护，最短 1 字符、最长 120 字符',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '热词展示顺序；数值越小越靠前',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '记录创建时间',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '记录最近更新时间',
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '软删除标记；整表替换时旧行置 TRUE',
    PRIMARY KEY (id),
    KEY idx_search_hot_keywords_order (is_deleted, sort_order, id) COMMENT '按未删除、展示顺序读取热词',
    CONSTRAINT chk_search_hot_keywords_keyword CHECK (CHAR_LENGTH(keyword) BETWEEN 1 AND 120)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS shop_info (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '店铺信息主键；仅作为 DEFAULT 单行配置入口',
    config_key VARCHAR(32) NOT NULL DEFAULT 'DEFAULT' COMMENT '配置标识；DEFAULT 表示单一运营主体的店铺信息',
    name VARCHAR(120) NOT NULL COMMENT '店铺名称',
    slogan VARCHAR(200) NOT NULL COMMENT '店铺口号',
    address VARCHAR(300) NOT NULL COMMENT '店铺地址',
    hours VARCHAR(120) NOT NULL COMMENT '营业时间',
    phone VARCHAR(20) NOT NULL COMMENT '联系电话；中国大陆手机号格式',
    wechat VARCHAR(128) NOT NULL COMMENT '联系微信号',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '记录创建时间',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '记录最近更新时间',
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '软删除标记；正常配置为 FALSE',
    PRIMARY KEY (id),
    UNIQUE KEY uk_shop_info_key (config_key) COMMENT '保证只有一行店铺配置',
    CONSTRAINT chk_shop_info_phone CHECK (REGEXP_LIKE(phone, '^1[3-9][0-9]{9}$'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 店铺信息初始值：取自小程序 config/shop.ts 当前写死的真实门店信息。
-- 写入后可在管理端「店铺信息」页修改，改完即对小程序生效，无需重新发版。
INSERT INTO shop_info (config_key, name, slogan, address, hours, phone, wechat)
VALUES ('DEFAULT', '小陈食品', '火锅食材供应 · 成都', '成都市青白江区 冷链物流园 B 区 12 号',
        '每天 06:00 – 20:00', '13800006688', 'xiaochen-food')
ON DUPLICATE KEY UPDATE updated_at = CURRENT_TIMESTAMP(6);

-- 热词初始值：只用线上真实能搜到结果的词；仅在表为空时写入，重复执行不会产生重复行。
INSERT INTO search_hot_keywords (keyword, sort_order)
SELECT * FROM (
    SELECT '雪花牛肉' AS keyword, 0 AS sort_order UNION ALL
    SELECT '牛毛肚', 1 UNION ALL
    SELECT '鸡爪',   2 UNION ALL
    SELECT '牛肉',   3 UNION ALL
    SELECT '毛肚',   4 UNION ALL
    SELECT '猪肉',   5
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM search_hot_keywords WHERE is_deleted = FALSE);

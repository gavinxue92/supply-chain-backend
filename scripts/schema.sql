-- Create the database first with scripts/create-database.sql.
-- Command: mysql -h localhost -P 3306 -u root -p supply_chain < scripts/schema.sql
-- Every table uses the agreed MySQL 8.0 engine, charset, collation, and common audit fields.
-- This is a full rebuild script for the development stage; no in-place migration is provided.

CREATE TABLE admin_users (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '管理员主键；被 admin_sessions、media_resources.uploaded_by、audit_logs.actor_admin_user_id 引用',
    username VARCHAR(80) NOT NULL COMMENT '后台登录用户名；全局唯一',
    nickname VARCHAR(50) NULL COMMENT '后台账号昵称；可选、非唯一，可由本账号或管理员维护',
    password_hash VARCHAR(255) NOT NULL COMMENT '密码的安全哈希值；不保存明文密码',
    role VARCHAR(32) NOT NULL COMMENT '后台角色；仅允许 ADMIN（管理员）或 EDITOR（运营编辑）',
    is_active BOOLEAN NOT NULL DEFAULT TRUE COMMENT '账号启用状态；停用后禁止登录并撤销关联会话',
    must_change_password BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否要求账号首次登录后修改临时密码',
    failed_login_attempts INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '连续密码校验失败次数；成功登录后清零',
    locked_until DATETIME(6) NULL COMMENT '账号锁定截止时间；NULL 表示当前未锁定',
    last_login_at DATETIME(6) NULL COMMENT '最近一次成功登录时间',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '记录创建时间',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '记录最近更新时间',
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '历史兼容字段；停用后硬删除为物理删除，不再依赖该标记过滤账号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_admin_users_username (username) COMMENT '保证后台登录用户名全局唯一，支持按用户名查找账号',
    KEY idx_admin_users_active_role (is_active, role) COMMENT '按启用状态和角色筛选后台账号',
    CONSTRAINT chk_admin_users_role CHECK (role IN ('ADMIN', 'EDITOR')),
    CONSTRAINT chk_admin_users_nickname_length CHECK (nickname IS NULL OR CHAR_LENGTH(nickname) <= 50)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE admin_sessions (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '后台会话主键',
    admin_user_id BIGINT UNSIGNED NOT NULL COMMENT '所属管理员；外键关联 admin_users.id',
    session_token_hash CHAR(64) NOT NULL COMMENT '会话令牌的 SHA-256 十六进制摘要；不保存原始令牌',
    last_activity_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '最近一次有效请求时间；用于判断空闲超时',
    expires_at DATETIME(6) NOT NULL COMMENT '会话绝对过期时间；有效期内活动请求会刷新',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '会话创建时间',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '会话记录最近更新时间',
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'TRUE means this session has been revoked',
    PRIMARY KEY (id),
    UNIQUE KEY uk_admin_sessions_token_hash (session_token_hash) COMMENT '保证会话令牌摘要唯一，支持通过摘要验证会话',
    KEY idx_admin_sessions_user_active (admin_user_id, is_deleted, expires_at) COMMENT '按管理员查找未撤销且未过期的会话',
    CONSTRAINT fk_admin_sessions_user
        FOREIGN KEY (admin_user_id) REFERENCES admin_users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_admin_sessions_expiry CHECK (expires_at > created_at)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE media_resources (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '媒体资源主键；可被产品主图、轮播图、客服二维码引用',
    object_key VARCHAR(512) NOT NULL COMMENT '对象存储中的唯一对象键；对应 S3 兼容存储对象',
    original_filename VARCHAR(255) NULL COMMENT '运营人员上传时的原始文件名，仅供管理端展示',
    media_type VARCHAR(32) NOT NULL COMMENT '媒体 MIME 类型；当前只允许 JPEG、PNG、WebP 图片',
    byte_size BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '文件字节数；不得超过 10 MB',
    resource_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '资源状态：PENDING 待确认、READY 可用、REJECTED 拒绝、DELETING 待删除对象',
    public_url VARCHAR(2048) NULL COMMENT '客户端公开读取图片的 URL；资源就绪后填写',
    uploaded_by BIGINT UNSIGNED NULL COMMENT '上传操作人；外键关联 admin_users.id，账号硬删除后置 NULL',
    upload_expires_at DATETIME(6) NULL COMMENT '直传凭证或待确认上传的有效截止时间',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '资源记录创建时间',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '资源记录最近更新时间',
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '历史兼容字段；媒体最终通过物理删除移除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_media_resources_object_key (object_key) COMMENT '防止同一对象存储键被重复登记',
    KEY idx_media_resources_status (resource_status, is_deleted) COMMENT '按资源状态和标记筛选待处理或可用资源',
    CONSTRAINT fk_media_resources_uploader
        FOREIGN KEY (uploaded_by) REFERENCES admin_users (id)
        ON DELETE SET NULL ON UPDATE RESTRICT,
    CONSTRAINT chk_media_resources_type CHECK (media_type IN ('image/jpeg', 'image/png', 'image/webp')),
    CONSTRAINT chk_media_resources_size CHECK (byte_size <= 10485760),
    CONSTRAINT chk_media_resources_status CHECK (resource_status IN ('PENDING', 'READY', 'REJECTED', 'DELETING'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE categories (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '分类主键；可被 products.category_id 引用',
    parent_id BIGINT UNSIGNED NULL COMMENT '父级分类 ID；自引用 categories.id，NULL 表示一级分类',
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '软删除标记；存在子分类或产品时业务规则禁止删除',
    parent_key BIGINT UNSIGNED
        GENERATED ALWAYS AS (
            CASE WHEN is_deleted THEN NULL ELSE IFNULL(parent_id, 0) END
        ) STORED COMMENT '有效分类的父级唯一性辅助键；删除后允许复用同级名称',
    name VARCHAR(120) NOT NULL COMMENT '分类名称；在同一父分类下唯一',
    active_name VARCHAR(120)
        GENERATED ALWAYS AS (CASE WHEN is_deleted THEN NULL ELSE name END) STORED
        COMMENT '有效分类的唯一名称；删除后允许复用同级名称',
    is_active BOOLEAN NOT NULL DEFAULT TRUE COMMENT '分类启用状态；停用一级分类时其分类树对客户隐藏',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '同级分类的运营展示顺序；数值越小越靠前',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '分类创建时间',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '分类最近更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_categories_active_parent_name (parent_key, active_name)
        COMMENT '保证有效分类在同一父级下名称唯一，删除后可复用名称',
    KEY idx_categories_parent_active_order (parent_id, is_active, sort_order) COMMENT '按父分类、启用状态和排序值读取分类列表',
    CONSTRAINT fk_categories_parent
        FOREIGN KEY (parent_id) REFERENCES categories (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE customer_services (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '客服联系人主键；由管理员创建，可被全局配置或产品挂载',
    display_name VARCHAR(120) NOT NULL COMMENT '客服展示名称；小程序按此名称展示',
    wechat_id VARCHAR(128) NULL COMMENT '客服微信号；与二维码、手机号至少提供一项',
    qr_media_id BIGINT UNSIGNED NULL COMMENT '客服二维码媒体 ID；外键关联 media_resources.id',
    phone VARCHAR(20) NULL COMMENT '客服手机号；中国大陆手机号格式，非登录凭据',
    is_active BOOLEAN NOT NULL DEFAULT TRUE COMMENT '客服启用状态；停用后不可新挂载且不公开展示',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '客服创建时间',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '客服最近更新时间',
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '历史兼容字段；客服硬删除为物理删除',
    PRIMARY KEY (id),
    KEY idx_customer_services_active (is_active, id) COMMENT '按启用状态读取可挂载客服',
    CONSTRAINT fk_customer_services_qr
        FOREIGN KEY (qr_media_id) REFERENCES media_resources (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_customer_services_contact CHECK (wechat_id IS NOT NULL OR qr_media_id IS NOT NULL OR phone IS NOT NULL),
    CONSTRAINT chk_customer_services_phone CHECK (phone IS NULL OR REGEXP_LIKE(phone, '^1[3-9][0-9]{9}$'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE global_contact_configs (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '全局客服配置主键；仅作为 GLOBAL 默认客服列表入口',
    config_key VARCHAR(32) NOT NULL DEFAULT 'GLOBAL' COMMENT '配置标识；GLOBAL 表示单一运营主体的全局默认客服配置',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '配置创建时间',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '配置最近更新时间',
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '软删除标记；正常配置为 FALSE',
    PRIMARY KEY (id),
    UNIQUE KEY uk_global_contact_configs_key (config_key) COMMENT '保证每种配置标识仅有一条记录，防止重复 GLOBAL 配置'
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE global_contact_services (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '全局客服关联主键',
    global_config_id BIGINT UNSIGNED NOT NULL COMMENT '全局客服配置 ID；外键关联 global_contact_configs.id',
    customer_service_id BIGINT UNSIGNED NOT NULL COMMENT '客服联系人 ID；外键关联 customer_services.id',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '全局客服展示顺序；数值越小越靠前',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '关联创建时间',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '关联最近更新时间',
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '历史兼容字段；关联最终通过物理删除移除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_global_contact_services_config_service (global_config_id, customer_service_id),
    UNIQUE KEY uk_global_contact_services_config_order (global_config_id, sort_order),
    CONSTRAINT fk_global_contact_services_config
        FOREIGN KEY (global_config_id) REFERENCES global_contact_configs (id)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_global_contact_services_service
        FOREIGN KEY (customer_service_id) REFERENCES customer_services (id)
        ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE products (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '产品主键；被 product_specs、product_keywords、product_gallery、product_contact_services 引用',
    name VARCHAR(200) NULL COMMENT '食材产品名称；草稿阶段可暂为空',
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT '产品生命周期：DRAFT 草稿、PUBLISHED 已上架、UNPUBLISHED 已下架、ARCHIVED 已归档',
    category_id BIGINT UNSIGNED NULL COMMENT '所属末级分类 ID；外键关联 categories.id，草稿阶段可暂为空',
    main_image_media_id BIGINT UNSIGNED NULL COMMENT '产品主图媒体 ID；外键关联 media_resources.id',
    detail_html LONGTEXT NULL COMMENT '产品图文详情的受限 HTML 内容；由服务端清洗并限制可引用图片',
    contact_mode VARCHAR(32) NOT NULL DEFAULT 'INHERIT' COMMENT '产品客服策略：INHERIT 继承全局、OVERRIDE 使用挂载客服、DISABLED 关闭客服入口',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '产品运营排序权重；用于同一目录下展示顺序',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '产品创建时间',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '产品最近更新时间',
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '归档标记；与 status=ARCHIVED 保持一致，硬删除时物理移除产品行',
    PRIMARY KEY (id),
    KEY idx_products_category_status_order (category_id, status, sort_order) COMMENT '按分类和产品状态筛选目录，并按运营排序读取产品',
    KEY idx_products_status_updated (status, updated_at) COMMENT '按产品状态和更新时间筛选或排序产品',
    CONSTRAINT fk_products_category
        FOREIGN KEY (category_id) REFERENCES categories (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_products_main_image
        FOREIGN KEY (main_image_media_id) REFERENCES media_resources (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_products_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'UNPUBLISHED', 'ARCHIVED')),
    CONSTRAINT chk_products_contact_mode CHECK (contact_mode IN ('INHERIT', 'OVERRIDE', 'DISABLED')),
    CONSTRAINT chk_products_archive_flag CHECK (
        (status = 'ARCHIVED' AND is_deleted = TRUE)
        OR (status <> 'ARCHIVED' AND is_deleted = FALSE)
    )
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE product_contact_services (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '产品客服挂载主键',
    product_id BIGINT UNSIGNED NOT NULL COMMENT '产品 ID；外键关联 products.id',
    customer_service_id BIGINT UNSIGNED NOT NULL COMMENT '客服联系人 ID；外键关联 customer_services.id',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '产品内客服展示顺序；数值越小越靠前',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '关联创建时间',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '关联最近更新时间',
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '历史兼容字段；关联最终通过物理删除移除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_contact_services_product_service (product_id, customer_service_id),
    UNIQUE KEY uk_product_contact_services_product_order (product_id, sort_order),
    CONSTRAINT fk_product_contact_services_product
        FOREIGN KEY (product_id) REFERENCES products (id)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_product_contact_services_service
        FOREIGN KEY (customer_service_id) REFERENCES customer_services (id)
        ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE product_specs (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '产品规格主键',
    product_id BIGINT UNSIGNED NOT NULL COMMENT '所属产品 ID；外键关联 products.id',
    name VARCHAR(120) NOT NULL COMMENT '规格展示名称，例如 500g 或 1kg；不是可购买 SKU',
    reference_price DECIMAL(10,2) NULL COMMENT '人民币参考价，精确到分；NULL 表示面议，不构成交易承诺',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '同一产品规格的运营展示顺序；数值越小越靠前',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '规格创建时间',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '规格最近更新时间',
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '软删除标记；TRUE 时该规格不再对外展示',
    PRIMARY KEY (id),
    KEY idx_product_specs_product_order (product_id, is_deleted, sort_order) COMMENT '按产品读取未删除规格，并按运营排序展示',
    CONSTRAINT fk_product_specs_product
        FOREIGN KEY (product_id) REFERENCES products (id)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT chk_product_specs_reference_price
        CHECK (reference_price IS NULL OR reference_price > 0)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE product_keywords (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '产品搜索关键词主键',
    product_id BIGINT UNSIGNED NOT NULL COMMENT '所属产品 ID；外键关联 products.id',
    keyword VARCHAR(120) NOT NULL COMMENT '运营维护的搜索关键词或别名；参与产品搜索匹配',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '关键词创建时间',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '关键词最近更新时间',
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '软删除标记；TRUE 时关键词不参与搜索',
    PRIMARY KEY (id),
    KEY idx_product_keywords_keyword (keyword, is_deleted) COMMENT '按关键词查找有效搜索别名',
    KEY idx_product_keywords_product (product_id, is_deleted) COMMENT '按产品读取未删除的搜索关键词',
    CONSTRAINT fk_product_keywords_product
        FOREIGN KEY (product_id) REFERENCES products (id)
        ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE product_gallery (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '产品轮播图关联记录主键',
    product_id BIGINT UNSIGNED NOT NULL COMMENT '所属产品 ID；外键关联 products.id',
    media_id BIGINT UNSIGNED NOT NULL COMMENT '轮播图片媒体 ID；外键关联 media_resources.id',
    sort_order TINYINT UNSIGNED NOT NULL COMMENT '轮播图顺序位置；每个产品的有效位置为 0 至 7',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '轮播图关联创建时间',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '轮播图关联最近更新时间',
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '软删除标记；TRUE 时该图片不再作为产品轮播图展示',
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_gallery_product_order (product_id, sort_order) COMMENT '保证同一产品的轮播图顺序位置不重复',
    UNIQUE KEY uk_product_gallery_product_media (product_id, media_id) COMMENT '防止同一媒体资源在同一产品轮播图中重复关联',
    CONSTRAINT fk_product_gallery_product
        FOREIGN KEY (product_id) REFERENCES products (id)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_product_gallery_media
        FOREIGN KEY (media_id) REFERENCES media_resources (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_product_gallery_order CHECK (sort_order < 8)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE audit_logs (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '操作审计记录主键',
    actor_admin_user_id BIGINT UNSIGNED NULL COMMENT '操作人管理员 ID；外键关联 admin_users.id，账号硬删除后置 NULL；摘要中保留操作者快照',
    action VARCHAR(80) NOT NULL COMMENT '操作事件名称，例如 ADMIN_ACCOUNT_CREATED',
    target_type VARCHAR(80) NOT NULL COMMENT '被操作对象类型，例如 admin_users 或 products',
    target_id VARCHAR(128) NULL COMMENT '被操作对象标识；使用字符串以兼容不同类型业务主键',
    change_summary JSON NULL COMMENT '变更摘要 JSON；不得记录密码、会话令牌等敏感值',
    occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '业务操作实际发生时间',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '审计记录写入时间',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '审计记录最近更新时间',
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'May be set only by the retention process',
    PRIMARY KEY (id),
    KEY idx_audit_logs_occurred_at (occurred_at) COMMENT '按发生时间查询审计记录并支持保留期清理',
    KEY idx_audit_logs_actor (actor_admin_user_id, occurred_at) COMMENT '按操作人和发生时间检索审计记录',
    KEY idx_audit_logs_target (target_type, target_id, occurred_at) COMMENT '按被操作对象及发生时间追踪审计记录',
    CONSTRAINT fk_audit_logs_actor
        FOREIGN KEY (actor_admin_user_id) REFERENCES admin_users (id)
        ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

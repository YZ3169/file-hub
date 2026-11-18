-- file-hub v4 迁移：用户、会话、下载票据、文件归属
--
-- 新库直接执行 src/main/resources/schema.sql 即可，本脚本只给已有库升级用。
-- 脚本幂等：表/列/索引存在时只打印「已存在，跳过」。
--
-- 用法：mysql -uroot -p file_hub < scripts/migrate-v4-auth.sql
--
-- 注意：旧 file_info 行的 owner_id 保持 NULL。按产品约定，NULL 旧数据只有
-- ADMIN 可见；不会在迁移里猜一个所有者，也不会删除任何文件。

USE `file_hub`;

CREATE TABLE IF NOT EXISTS `user_account`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `username`      VARCHAR(32)  NOT NULL COMMENT '登录名（唯一，大小写不敏感）',
    `password_hash` VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码摘要，绝不存明文',
    `role`          VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT 'USER / ADMIN',
    `enabled`       TINYINT      NOT NULL DEFAULT 1 COMMENT '1=可登录',
    `created_at`    DATETIME     NOT NULL,
    `updated_at`    DATETIME     NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户表';

CREATE TABLE IF NOT EXISTS `user_session`
(
    `id`           BIGINT      NOT NULL AUTO_INCREMENT,
    `user_id`      BIGINT      NOT NULL,
    `token_hash`   CHAR(64)    NOT NULL COMMENT '随机 Token 的 SHA-256',
    `expires_at`   DATETIME    NOT NULL,
    `created_at`   DATETIME    NOT NULL,
    `last_used_at` DATETIME             DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_token_hash` (`token_hash`),
    KEY `idx_session_user` (`user_id`),
    KEY `idx_session_expires` (`expires_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='登录会话（不透明 Bearer Token）';

CREATE TABLE IF NOT EXISTS `download_ticket`
(
    `id`          BIGINT      NOT NULL AUTO_INCREMENT,
    `ticket_hash` CHAR(64)    NOT NULL,
    `file_id`     VARCHAR(64) NOT NULL,
    `user_id`     BIGINT      NOT NULL,
    `expires_at`  DATETIME    NOT NULL,
    `created_at`  DATETIME    NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ticket_hash` (`ticket_hash`),
    KEY `idx_ticket_expires` (`expires_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='浏览器下载用短期票据';

-- owner_id：已有列时跳过
SET @col_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'file_info'
      AND column_name = 'owner_id'
);
SET @ddl = IF(
    @col_exists = 0,
    'ALTER TABLE `file_info` ADD COLUMN `owner_id` BIGINT DEFAULT NULL COMMENT ''所属用户；旧数据为 NULL，仅管理员可见'' AFTER `status`',
    'SELECT ''file_info.owner_id 已存在，跳过'' AS msg'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 用户列表索引
SET @idx_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'file_info'
      AND index_name = 'idx_owner_status_id'
);
SET @ddl = IF(
    @idx_exists = 0,
    'ALTER TABLE `file_info` ADD INDEX `idx_owner_status_id` (`owner_id`, `status`, `id`)',
    'SELECT ''idx_owner_status_id 已存在，跳过'' AS msg'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 用户级秒传检查索引
SET @idx_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'file_info'
      AND index_name = 'idx_owner_hash_size_status'
);
SET @ddl = IF(
    @idx_exists = 0,
    'ALTER TABLE `file_info` ADD INDEX `idx_owner_hash_size_status` (`owner_id`, `file_hash`, `file_size`, `status`)',
    'SELECT ''idx_owner_hash_size_status 已存在，跳过'' AS msg'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

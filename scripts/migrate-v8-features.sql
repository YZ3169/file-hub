-- file-hub v8 迁移：配额、回收站、免密/限次分享、显式文件夹与操作审计。
-- 新库直接执行 src/main/resources/schema.sql 即可；已有库须在启动新版服务前执行本脚本。
-- 脚本幂等，可重复执行。
-- 用法：mysql -uroot -p file_hub < scripts/migrate-v8-features.sql

USE `file_hub`;

-- 1. user_account.storage_quota_bytes
SET @quota_col_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'user_account' AND column_name = 'storage_quota_bytes'
);
SET @quota_col_sql = IF(@quota_col_exists = 0,
    'ALTER TABLE `user_account` ADD COLUMN `storage_quota_bytes` BIGINT NOT NULL DEFAULT -1 COMMENT ''存储配额（字节），-1 表示不限制'' AFTER `enabled`',
    'SELECT ''user_account.storage_quota_bytes already exists'' AS message'
);
PREPARE stmt FROM @quota_col_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. file_info.deleted_at & idx_status_deleted
SET @deleted_at_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'file_info' AND column_name = 'deleted_at'
);
SET @deleted_at_sql = IF(@deleted_at_exists = 0,
    'ALTER TABLE `file_info` ADD COLUMN `deleted_at` DATETIME DEFAULT NULL COMMENT ''移入回收站时间'' AFTER `owner_id`',
    'SELECT ''file_info.deleted_at already exists'' AS message'
);
PREPARE stmt FROM @deleted_at_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_deleted_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'file_info' AND index_name = 'idx_status_deleted'
);
SET @idx_deleted_sql = IF(@idx_deleted_exists = 0,
    'ALTER TABLE `file_info` ADD KEY `idx_status_deleted` (`status`, `deleted_at`)',
    'SELECT ''idx_status_deleted already exists'' AS message'
);
PREPARE stmt FROM @idx_deleted_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. file_share.require_code & max_downloads
SET @require_code_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'file_share' AND column_name = 'require_code'
);
SET @require_code_sql = IF(@require_code_exists = 0,
    'ALTER TABLE `file_share` ADD COLUMN `require_code` TINYINT NOT NULL DEFAULT 1 COMMENT ''1=需要提取码 0=免提取码公开链接'' AFTER `code_hash`',
    'SELECT ''file_share.require_code already exists'' AS message'
);
PREPARE stmt FROM @require_code_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @max_dl_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'file_share' AND column_name = 'max_downloads'
);
SET @max_dl_sql = IF(@max_dl_exists = 0,
    'ALTER TABLE `file_share` ADD COLUMN `max_downloads` BIGINT DEFAULT NULL COMMENT ''最大允许验证/下载次数，NULL=不限制'' AFTER `download_count`',
    'SELECT ''file_share.max_downloads already exists'' AS message'
);
PREPARE stmt FROM @max_dl_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4. file_folder
CREATE TABLE IF NOT EXISTS `file_folder`
(
    `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `owner_id`         BIGINT        NOT NULL COMMENT '所属用户',
    `folder_path`      VARCHAR(4096) NOT NULL COMMENT '相对目录路径（不含首尾斜杠）',
    `folder_path_hash` CHAR(64)      NOT NULL COMMENT 'folder_path 的 SHA-256（大小写敏感唯一约束）',
    `create_time`      DATETIME      NOT NULL,
    `update_time`      DATETIME      NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_owner_folder_hash` (`owner_id`, `folder_path_hash`),
    KEY `idx_folder_owner` (`owner_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='显式文件夹记录';

-- 5. audit_log
CREATE TABLE IF NOT EXISTS `audit_log`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `user_id`     BIGINT                DEFAULT NULL COMMENT '操作人 ID，匿名操作为 NULL',
    `username`    VARCHAR(64)           DEFAULT NULL COMMENT '操作人用户名',
    `action`      VARCHAR(64)  NOT NULL COMMENT '动作标识',
    `target_type` VARCHAR(32)           DEFAULT NULL COMMENT '对象类型',
    `target_id`   VARCHAR(128)          DEFAULT NULL COMMENT '对象标识',
    `detail`      VARCHAR(512)          DEFAULT NULL COMMENT '详情摘要',
    `client_ip`   VARCHAR(64)           DEFAULT NULL COMMENT '来源 IP',
    `created_at`  DATETIME     NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_audit_user_time` (`user_id`, `created_at`),
    KEY `idx_audit_created` (`created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='操作审计日志';

-- 6. zip_ticket
CREATE TABLE IF NOT EXISTS `zip_ticket`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `ticket_hash` CHAR(64)     NOT NULL,
    `user_id`     BIGINT       NOT NULL,
    `zip_name`    VARCHAR(255) NOT NULL,
    `payload`     MEDIUMTEXT   NOT NULL,
    `expires_at`  DATETIME     NOT NULL,
    `created_at`  DATETIME     NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_zip_ticket_hash` (`ticket_hash`),
    KEY `idx_zip_ticket_expires` (`expires_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='打包下载短期票据';

-- 7. download_ticket.share_id & idx_ticket_share（取消分享时立即吊销已签发票据）
SET @ticket_share_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'download_ticket' AND column_name = 'share_id'
);
SET @ticket_share_sql = IF(@ticket_share_exists = 0,
    'ALTER TABLE `download_ticket` ADD COLUMN `share_id` BIGINT DEFAULT NULL COMMENT ''关联分享 ID（取消或重置提取码时立即吊销）'' AFTER `user_id`',
    'SELECT ''download_ticket.share_id already exists'' AS message'
);
PREPARE stmt FROM @ticket_share_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_ticket_share_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'download_ticket' AND index_name = 'idx_ticket_share'
);
SET @idx_ticket_share_sql = IF(@idx_ticket_share_exists = 0,
    'ALTER TABLE `download_ticket` ADD KEY `idx_ticket_share` (`share_id`)',
    'SELECT ''idx_ticket_share already exists'' AS message'
);
PREPARE stmt FROM @idx_ticket_share_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

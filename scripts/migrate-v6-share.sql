-- file-hub v6 迁移：提取码分享
--
-- 新库直接执行 src/main/resources/schema.sql 即可，本脚本只给已有库升级用。
-- 只新增 file_share 表，不修改任何已有数据；脚本幂等，可重复执行。
--
-- 用法：mysql -uroot -p file_hub < scripts/migrate-v6-share.sql

USE `file_hub`;

CREATE TABLE IF NOT EXISTS `file_share`
(
    `id`              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `share_token`     VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '链接中的随机标识（43 字符）；只是定位符，不是口令。必须大小写敏感',
    `file_id`         VARCHAR(64) NOT NULL COMMENT '被分享文件',
    `owner_id`        BIGINT      NOT NULL COMMENT '创建者，也是匿名下载票据的签发主体',
    `code_hash`       VARCHAR(100) NOT NULL COMMENT '4 位提取码的 BCrypt 摘要，绝不存明文',
    `expires_at`      DATETIME             DEFAULT NULL COMMENT 'NULL=永久有效',
    `status`          TINYINT     NOT NULL DEFAULT 1 COMMENT '1=有效 0=已取消',
    `download_count`  BIGINT      NOT NULL DEFAULT 0 COMMENT '成功校验提取码的次数',
    `failed_attempts` INT         NOT NULL DEFAULT 0 COMMENT '当前窗口内连续输错次数',
    `locked_until`    DATETIME             DEFAULT NULL COMMENT '锁定截止时间；NULL=未锁定',
    `create_time`     DATETIME    NOT NULL,
    `update_time`     DATETIME    NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_share_token` (`share_token`),
    KEY `idx_share_owner` (`owner_id`, `id`),
    KEY `idx_share_file` (`file_id`),
    KEY `idx_share_status_expires` (`status`, `expires_at`),
    KEY `idx_share_update_time` (`update_time`),
    KEY `idx_share_expires` (`expires_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='文件分享（链接 + 提取码）';

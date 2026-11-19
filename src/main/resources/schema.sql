-- file-hub 建表脚本
-- 用法：mysql -uroot -p < schema.sql
-- 设计说明见 README「表结构为什么长这样」

CREATE DATABASE IF NOT EXISTS `file_hub`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE `file_hub`;

-- ---------------------------------------------------------------------------
-- 用户与会话
-- ---------------------------------------------------------------------------

DROP TABLE IF EXISTS `user_account`;
CREATE TABLE `user_account`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
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

DROP TABLE IF EXISTS `user_session`;
CREATE TABLE `user_session`
(
    `id`           BIGINT      NOT NULL AUTO_INCREMENT,
    `user_id`      BIGINT      NOT NULL,
    `token_hash`   CHAR(64)    NOT NULL COMMENT '随机 Token 的 SHA-256；数据库泄漏也拿不到原 Token',
    `expires_at`   DATETIME    NOT NULL,
    `created_at`   DATETIME    NOT NULL,
    `last_used_at` DATETIME             DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_token_hash` (`token_hash`),
    KEY `idx_session_user` (`user_id`),
    KEY `idx_session_expires` (`expires_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='登录会话（不透明 Bearer Token）';

DROP TABLE IF EXISTS `download_ticket`;
CREATE TABLE `download_ticket`
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

-- 文件主表。一行 = 一个逻辑文件（同一个物理文件被秒传命中时会有多行共享 storage_path）
DROP TABLE IF EXISTS `file_info`;
CREATE TABLE `file_info`
(
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `file_id`      VARCHAR(64)  NOT NULL COMMENT '对外暴露的文件标识（UUID，不暴露自增 ID）',
    `file_name`    VARCHAR(255) NOT NULL COMMENT '原始文件名（含扩展名）',
    `file_size`    BIGINT       NOT NULL COMMENT '文件总字节数',
    `file_hash`    CHAR(64)     NOT NULL COMMENT '全文件 SHA-256（hex，小写），秒传与完整性校验都靠它',
    `chunk_size`   INT          NOT NULL COMMENT '该次上传约定的分片大小',
    `chunk_total`  INT          NOT NULL COMMENT '分片总数',
    `storage_path` VARCHAR(500)          DEFAULT NULL COMMENT '成品在存储后端中的相对路径；上传中为 NULL',
    `status`       TINYINT      NOT NULL DEFAULT 0 COMMENT '0=上传中 1=已完成',
    `owner_id`     BIGINT                DEFAULT NULL COMMENT '所属用户；旧数据为 NULL，仅管理员可见',
    `create_time`  DATETIME     NOT NULL COMMENT '创建时间',
    `update_time`  DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_id` (`file_id`),
    -- 秒传的查询条件就是 (file_hash, file_size, status)，做成联合索引直接命中
    KEY `idx_hash_size_status` (`file_hash`, `file_size`, `status`),
    -- 删除时按 storage_path 数引用行数，秒传产生的多行会共享同一个物理文件
    KEY `idx_storage_path` (`storage_path`),
    -- 定时清理的查询条件就是 (status = 0, update_time < cutoff)
    KEY `idx_status_update` (`status`, `update_time`),
    -- 列表按状态过滤后按 id 倒序分页
    KEY `idx_status_id` (`status`, `id`),
    -- 用户列表：先按 owner 过滤，再按 status 过滤/排序
    KEY `idx_owner_status_id` (`owner_id`, `status`, `id`),
    -- 用户级秒传检查
    KEY `idx_owner_hash_size_status` (`owner_id`, `file_hash`, `file_size`, `status`),
    -- 中文文件名搜索：ngram 把 "测试报告" 切成二元组，比 LIKE '%关键词%' 全表扫描快得多。
    -- 需要 MySQL 8（内置 ngram 解析器），MySQL 5.7 没有这个 parser。
    FULLTEXT KEY `ft_file_name` (`file_name`) WITH PARSER ngram
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='文件主表';

-- 分片表。一行 = 一个已成功落盘的分片
DROP TABLE IF EXISTS `file_chunk`;
CREATE TABLE `file_chunk`
(
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `file_id`     VARCHAR(64) NOT NULL COMMENT '所属文件标识',
    `chunk_index` INT         NOT NULL COMMENT '分片序号，从 0 开始',
    `chunk_size`  INT         NOT NULL COMMENT '该分片实际字节数（最后一片通常更小）',
    `chunk_hash`  CHAR(64)             DEFAULT NULL COMMENT '该分片的 SHA-256（hex 小写）；上传时校验、合并时逐片复算',
    `create_time` DATETIME    NOT NULL COMMENT '落盘时间',
    PRIMARY KEY (`id`),
    -- 关键：靠这个唯一索引把"重复上传同一分片"变成幂等操作（并发下第二次会撞唯一键而不是写坏数据）
    UNIQUE KEY `uk_file_chunk` (`file_id`, `chunk_index`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='文件分片表';

-- 分享表。一行 = 一个分享链接；提取码只存 BCrypt 摘要。
DROP TABLE IF EXISTS `file_share`;
CREATE TABLE `file_share`
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

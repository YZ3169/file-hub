-- file-hub 建表脚本
-- 用法：mysql -uroot -p < schema.sql
-- 设计说明见 README「表结构为什么长这样」

CREATE DATABASE IF NOT EXISTS `file_hub`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE `file_hub`;

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
    `create_time`  DATETIME     NOT NULL COMMENT '创建时间',
    `update_time`  DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_id` (`file_id`),
    -- 秒传的查询条件就是 (file_hash, file_size, status)，做成联合索引直接命中
    KEY `idx_hash_size_status` (`file_hash`, `file_size`, `status`),
    -- 删除时按 storage_path 数引用行数，秒传产生的多行会共享同一个物理文件
    KEY `idx_storage_path` (`storage_path`)
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
    `create_time` DATETIME    NOT NULL COMMENT '落盘时间',
    PRIMARY KEY (`id`),
    -- 关键：靠这个唯一索引把"重复上传同一分片"变成幂等操作（并发下第二次会撞唯一键而不是写坏数据）
    UNIQUE KEY `uk_file_chunk` (`file_id`, `chunk_index`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='文件分片表';

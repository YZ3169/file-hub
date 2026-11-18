-- file-hub v3 迁移：补齐清理/分页需要的索引
--
-- 1) idx_status_update：定时清理的 WHERE 条件是 status = 0 AND update_time < cutoff。
--    没有这个索引时每轮清理都要全表扫描，表一大就会拖慢数据库。
-- 2) idx_status_id：列表接口支持按 status 过滤并按 id 倒序分页，
--    联合索引让「先过滤再排序」走一次索引，而不是过滤后 filesort。
--
-- 脚本幂等：重复执行只会打印「已存在，跳过」。
-- 用法：mysql -uroot -p file_hub < scripts/migrate-v3-indexes.sql
--
-- 新建库直接跑 src/main/resources/schema.sql 即可，里面已经包含这两个索引。

USE `file_hub`;

SET @idx_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'file_info'
      AND index_name = 'idx_status_update'
);
SET @ddl = IF(
    @idx_exists = 0,
    'ALTER TABLE `file_info` ADD INDEX `idx_status_update` (`status`, `update_time`)',
    'SELECT ''idx_status_update 已存在，跳过'' AS msg'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'file_info'
      AND index_name = 'idx_status_id'
);
SET @ddl = IF(
    @idx_exists = 0,
    'ALTER TABLE `file_info` ADD INDEX `idx_status_id` (`status`, `id`)',
    'SELECT ''idx_status_id 已存在，跳过'' AS msg'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

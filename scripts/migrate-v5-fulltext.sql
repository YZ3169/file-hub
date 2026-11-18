-- Phase 5 迁移：给 file_info.file_name 加 ngram 全文索引。
-- 幂等：已有同名索引时什么都不做，可以反复执行。
-- 用法：mysql -h 127.0.0.1 -P 3307 -uroot < scripts/migrate-v5-fulltext.sql
--
-- 注意：大表上 ADD FULLTEXT 会重建索引，期间写操作可能被阻塞，建议低峰执行。
-- MySQL 5.7 不支持 ngram parser，请先确认版本（SELECT VERSION();）。

USE `file_hub`;

SET @has_ft := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'file_info'
      AND index_name = 'ft_file_name'
);

SET @ddl := IF(@has_ft = 0,
    'ALTER TABLE `file_info` ADD FULLTEXT KEY `ft_file_name` (`file_name`) WITH PARSER ngram',
    'DO 0');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

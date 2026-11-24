-- file-hub v7 迁移：保留文件夹上传的相对路径。
-- 新库使用 src/main/resources/schema.sql；已有库须在启动新版服务前执行本脚本。
-- 不修改文件名或物理存储路径，已有文件使用空相对路径；可重复执行。
-- 用法：mysql -uroot -p file_hub < scripts/migrate-v7-relative-path.sql

USE `file_hub`;

SET @relative_path_column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'file_info' AND column_name = 'relative_path'
);
SET @relative_path_migration_sql = IF(@relative_path_column_exists = 0,
    'ALTER TABLE `file_info` ADD COLUMN `relative_path` VARCHAR(4096) NOT NULL DEFAULT '''' COMMENT ''文件夹上传的相对路径（含文件名），普通文件为空'' AFTER `file_name`',
    'SELECT ''relative_path already exists'' AS message'
);
PREPARE relative_path_migration FROM @relative_path_migration_sql;
EXECUTE relative_path_migration;
DEALLOCATE PREPARE relative_path_migration;

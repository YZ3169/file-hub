-- file-hub v2 迁移：给 file_chunk 增加分片级 SHA-256
--
-- 老版本只有整文件哈希，某个分片在磁盘上悄悄坏掉时，要到合并阶段才能发现，
-- 而且只能让用户整文件重传。v2 把每个分片的摘要也落库，合并时逐片复算：
-- 坏哪片就只重传哪片。
--
-- 用法：mysql -uroot -p file_hub < scripts/migrate-v2-chunk-hash.sql

USE `file_hub`;

SET @column_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'file_chunk'
      AND column_name = 'chunk_hash'
);

SET @ddl = IF(
    @column_exists = 0,
    'ALTER TABLE `file_chunk` ADD COLUMN `chunk_hash` CHAR(64) DEFAULT NULL COMMENT ''该分片的 SHA-256（hex 小写）；上传时校验、合并时逐片复算'' AFTER `chunk_size`',
    'SELECT ''chunk_hash 已存在，跳过'' AS msg'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 历史分片没有摘要，保留 NULL：合并时会跳过摘要比对，但仍然校验大小。
-- 重新上传一次同一文件即可把新分片的摘要补齐。

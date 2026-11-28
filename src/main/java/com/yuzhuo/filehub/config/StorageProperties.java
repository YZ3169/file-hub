package com.yuzhuo.filehub.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 存储相关配置，对应 application.yml 里的 file-hub.* 节点。
 */
@Data
@ConfigurationProperties(prefix = "file-hub")
public class StorageProperties {

    /** 存储根目录，下面会分出 files/（成品）与 chunks/（上传中的临时分片） */
    private String root = "D:/Projects/file-hub-data";

    /** 约定分片大小（字节）。服务端用它校验前端声明的分片数，避免前端算错导致合并出坏文件 */
    private long chunkSize = 5L * 1024 * 1024;

    /** 上传任务超过这个时长没有新分片落盘，就认为已废弃，可被清理 */
    private int uploadExpireMinutes = 24 * 60;

    /** files/ 下无数据库引用的成品文件，超过这个时长才回收（小时） */
    private int orphanFileGraceHours = 7 * 24;

    /** 清理任务的执行间隔（毫秒） */
    private long cleanupIntervalMs = 10 * 60 * 1000L;

    /** 应用启动后第一次清理的延迟（毫秒），避免和启动自检抢 IO */
    private long cleanupInitialDelayMs = 60 * 1000L;

    /** 关键词搜索相关配置 */
    private Search search = new Search();

    /** 回收站文件保留天数（超过后自动彻底清理），默认 30 天 */
    private int trashRetentionDays = 30;

    /** 用户存储配额配置 */
    private Quota quota = new Quota();

    /** 单次流式上传（WebDAV PUT / 桌面目录同步）大小上限，默认 2GB */
    private long maxStreamUploadBytes = 2L * 1024 * 1024 * 1024;

    /** 成品对象存储后端配置：local（默认，本地磁盘）| s3 */
    private Storage storage = new Storage();

    @Data
    public static class Quota {
        /** 新用户默认配额（字节），-1 表示不限制 */
        private long defaultBytes = -1L;
    }

    @Data
    public static class Search {
        /**
         * 是否使用 MySQL 的 ngram 全文索引。桌面版 H2 不支持 MATCH...AGAINST，
         * 关掉后统一回退 LIKE。
         */
        private boolean fulltextEnabled = true;
    }

    @Data
    public static class Storage {
        /** local | s3 */
        private String type = "local";
        private S3 s3 = new S3();
    }

    @Data
    public static class S3 {
        /** S3/MinIO/OSS endpoint，如 http://127.0.0.1:9000；留空走 AWS 默认域名 */
        private String endpoint = "";
        private String region = "us-east-1";
        private String bucket = "file-hub";
        private String accessKey = "";
        private String secretKey = "";
        /** 对象键前缀，如 files/ */
        private String keyPrefix = "files/";
        /** MinIO/私有对象存储一般需要 path-style */
        private boolean pathStyleAccess = true;
        /** 超过该字节数自动走 S3 分片上传（Multipart Upload），默认 16MB */
        private long multipartThresholdBytes = 16L * 1024 * 1024;
        /** S3 分片上传每片大小（S3 要求非最后一片至少 5MB），默认 8MB */
        private long multipartPartBytes = 8L * 1024 * 1024;
        /** 启动时若 bucket 不存在则自动创建（方便本地 MinIO 开箱即用） */
        private boolean autoCreateBucket = true;
    }
}

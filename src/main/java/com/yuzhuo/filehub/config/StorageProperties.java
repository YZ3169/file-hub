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
}

package com.yuzhuo.filehub.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 多实例（集群）相关配置。
 *
 * <p>{@code enabled=false}（默认）时使用 JVM 内锁和 Caffeine 限流，不需要 Redis；
 * 打开后锁和限流都切到 Redis，Redis 不可用时请求会明确失败，而不是静默退回本地锁——
 * 静默降级会让两个实例同时写同一份文件。</p>
 */
@Data
@ConfigurationProperties(prefix = "file-hub.cluster")
public class ClusterProperties {

    /** 是否启用多实例模式（Redis 分布式锁 + Redis 限流） */
    private boolean enabled = false;

    /** Redis 单机地址，例如 redis://127.0.0.1:6379 */
    private String redisAddress = "redis://127.0.0.1:6379";

    /** Redis database 编号 */
    private int redisDatabase = 0;

    /** 所有 key 的统一前缀；方便清理和与其它应用共用同一个 Redis */
    private String keyPrefix = "filehub:";
}

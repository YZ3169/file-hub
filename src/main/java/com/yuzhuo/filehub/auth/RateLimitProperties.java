package com.yuzhuo.filehub.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 固定窗口限流阈值。分片阈值特意给得很高：1GB 文件也只有 205 片，重试空间要留够。
 */
@Data
@ConfigurationProperties(prefix = "file-hub.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;

    private int loginPerMinute = 10;

    private int registerPerHour = 20;

    private int initPerMinute = 60;

    private int chunkPerMinute = 2400;

    private int apiPerMinute = 600;

    /**
     * 默认只信任 remoteAddr。只有部署在明确配置过 X-Forwarded-For 的反向代理后面时才打开，
     * 否则客户端可以伪造该头绕过按 IP 的登录限流。
     */
    private boolean trustForwardedHeaders = false;
}

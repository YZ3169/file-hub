package com.yuzhuo.filehub.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 提取码分享的防爆破与清理参数。
 */
@Data
@ConfigurationProperties(prefix = "file-hub.share")
public class ShareProperties {

    /** 连续输错多少次锁定 */
    private int codeMaxAttempts = 10;

    /** 锁定时长（分钟） */
    private int codeLockMinutes = 15;

    /**
     * 提取码的服务器端密钥（pepper）。设置后 BCrypt 摘要的是 HMAC(code, pepper)，
     * 只有数据库、没有进程环境时无法离线穷举 4 位码。
     *
     * <p>为空表示不使用（兼容旧数据）。修改这个值会让已有分享全部失效，
     * 需要重生提取码。生产环境建议通过 FILE_HUB_SHARE_PEPPER 注入。</p>
     */
    private String codePepper = "";

    /** 单个文件最多同时存在多少个有效分享 */
    private int maxActivePerFile = 5;

    /** 每个用户最多同时存在多少个有效分享 */
    private int maxActivePerOwner = 100;

    /** 已取消/过期分享保留多少天后删除 */
    private int staleRetentionDays = 7;

    /** 清理任务间隔（毫秒） */
    private long cleanupIntervalMs = 1_800_000L;

    /** 启动后首次清理延迟（毫秒），避免与应用启动争 IO */
    private long cleanupInitialDelayMs = 60_000L;
}

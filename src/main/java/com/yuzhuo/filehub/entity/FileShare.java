package com.yuzhuo.filehub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条分享 = 一个链接 + 一个 4 位提取码。
 *
 * <p>{@code shareToken} 是 URL 中的定位符，存原文，方便「我的分享」重新拼链接；
 * 它本身不是口令。真正的访问控制靠 {@code codeHash}（BCrypt）和失败锁定。</p>
 */
@Data
@TableName("file_share")
public class FileShare {

    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_CANCELLED = 0;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String shareToken;

    private String fileId;

    private Long ownerId;

    /** 4 位提取码的 BCrypt 摘要；绝不返回给前端 */
    private String codeHash;

    /** NULL = 永久有效 */
    private LocalDateTime expiresAt;

    /** 1=有效 0=已取消 */
    private Integer status;

    private Long downloadCount;

    private Integer failedAttempts;

    /** 锁定截止时间；NULL 或已过期表示未锁定 */
    private LocalDateTime lockedUntil;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

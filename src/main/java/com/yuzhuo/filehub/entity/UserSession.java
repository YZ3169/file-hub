package com.yuzhuo.filehub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_session")
public class UserSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 原始 Token 的 SHA-256，不是 Token 本身 */
    private String tokenHash;

    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;

    private LocalDateTime lastUsedAt;
}

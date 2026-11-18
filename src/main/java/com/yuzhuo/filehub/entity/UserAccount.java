package com.yuzhuo.filehub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_account")
public class UserAccount {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ADMIN = "ADMIN";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt 摘要；绝不存明文，也绝不写日志 */
    private String passwordHash;

    /** USER / ADMIN */
    private String role;

    /** 1=可登录 */
    private Integer enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }

    /** 不要叫 isEnabled()：会和 Lombok 为 Integer 字段生成的 getEnabled() 冲突，MyBatis 报二义性。 */
    public boolean isActive() {
        return enabled != null && enabled == 1;
    }
}

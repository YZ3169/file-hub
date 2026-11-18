package com.yuzhuo.filehub.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 认证相关配置。管理员密码只从环境变量读取，默认值为空——空值时不会创建管理员。
 */
@Data
@ConfigurationProperties(prefix = "file-hub.auth")
public class AuthProperties {

    /** 登录 Token 有效期，默认 7 天 */
    private int tokenTtlHours = 168;

    /** 浏览器下载票据有效期，默认 5 分钟 */
    private int ticketTtlMinutes = 5;

    /** 是否允许自助注册 */
    private boolean allowRegistration = true;

    /** 桌面版：第一个注册的账号自动成为管理员（默认关闭，服务器模式不受影响） */
    private boolean firstUserAdmin = false;

    private String adminUsername = "admin";

    /** 由 FILE_HUB_ADMIN_PASSWORD 注入；不要写进仓库 */
    private String adminPassword = "";
}

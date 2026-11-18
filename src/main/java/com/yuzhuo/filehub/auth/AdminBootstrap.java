package com.yuzhuo.filehub.auth;

import com.yuzhuo.filehub.entity.UserAccount;
import com.yuzhuo.filehub.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * 启动时按环境变量创建/提升管理员。密码为空时什么都不做；
 * 已存在的管理员不会被重置密码（避免每次启动覆盖人工修改）。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    private final UserAccountMapper userMapper;

    private final PasswordEncoder passwordEncoder;

    private final AuthProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        String password = properties.getAdminPassword();
        if (password == null || password.isBlank()) {
            log.info("未设置 FILE_HUB_ADMIN_PASSWORD，跳过管理员初始化");
            return;
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72
                || password.length() < 8) {
            log.error("管理员密码不合法（至少 8 个字符且 UTF-8 不超过 72 字节），跳过初始化");
            return;
        }
        String username = properties.getAdminUsername();
        if (username == null || username.isBlank()) {
            username = "admin";
        }
        username = username.trim();

        UserAccount existing = userMapper.findByUsername(username);
        if (existing == null) {
            UserAccount account = new UserAccount();
            account.setUsername(username);
            account.setPasswordHash(passwordEncoder.encode(password));
            account.setRole(UserAccount.ROLE_ADMIN);
            account.setEnabled(1);
            account.setCreatedAt(LocalDateTime.now());
            account.setUpdatedAt(LocalDateTime.now());
            try {
                userMapper.insert(account);
                log.info("已创建管理员 username={}", username);
            } catch (DuplicateKeyException e) {
                // 多实例同时启动时另一个实例先插入，按已存在处理
                promoteExisting(username);
            }
            return;
        }
        if (!existing.isAdmin()) {
            existing.setRole(UserAccount.ROLE_ADMIN);
            existing.setUpdatedAt(LocalDateTime.now());
            userMapper.updateById(existing);
            log.info("已将用户提升为管理员 username={}", username);
        } else {
            log.info("管理员已存在，跳过创建 username={}", username);
        }
    }

    private void promoteExisting(String username) {
        UserAccount account = userMapper.findByUsername(username);
        if (account != null && !account.isAdmin()) {
            account.setRole(UserAccount.ROLE_ADMIN);
            account.setUpdatedAt(LocalDateTime.now());
            userMapper.updateById(account);
        }
        log.info("管理员已存在，跳过创建 username={}", username);
    }
}

package com.yuzhuo.filehub;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.yuzhuo.filehub.auth.AuthProperties;
import com.yuzhuo.filehub.auth.RateLimitProperties;
import com.yuzhuo.filehub.config.ClusterProperties;
import com.yuzhuo.filehub.config.ShareProperties;
import com.yuzhuo.filehub.config.StorageProperties;
import com.yuzhuo.filehub.desktop.DesktopLauncher;

// 自己做密码校验，排除默认 UserDetailsService，避免启动日志里出现随机密码
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableConfigurationProperties({StorageProperties.class, AuthProperties.class,
        RateLimitProperties.class, ClusterProperties.class, ShareProperties.class})
@EnableScheduling
@MapperScan("com.yuzhuo.filehub.mapper")
public class FileHubApplication {

    public static void main(String[] args) {
        // jpackage 启动器会传 -Dfilehub.desktop=true；普通 java -jar / Docker 不受影响。
        if (Boolean.getBoolean("filehub.desktop")) {
            DesktopLauncher.run(args);
            return;
        }
        SpringApplication.run(FileHubApplication.class, args);
    }
}

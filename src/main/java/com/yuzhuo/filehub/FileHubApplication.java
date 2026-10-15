package com.yuzhuo.filehub;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.yuzhuo.filehub.config.StorageProperties;

@SpringBootApplication
@EnableConfigurationProperties(StorageProperties.class)
@MapperScan("com.yuzhuo.filehub.mapper")
public class FileHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileHubApplication.class, args);
    }
}

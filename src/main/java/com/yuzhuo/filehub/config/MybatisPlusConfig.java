package com.yuzhuo.filehub.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 的分页插件。
 *
 * <p>不装这个插件时 {@code selectPage} 不会真的加 LIMIT，而是把整表查出来再截断，
 * 数据量一大就会变成「明明只查一页，却全表读一遍」。插件负责两件事：
 * 把 LIMIT 拼进 SQL，以及另发一条 COUNT 查总数。</p>
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}

package com.yuzhuo.filehub.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 描述。认证方式写清楚是「不透明 Bearer Token」而不是 JWT：
 * 登录接口返回的随机串，数据库只存它的 SHA-256，可以随时撤销。
 */
@Configuration
public class OpenApiConfig {

    public static final String BEARER = "bearerToken";

    @Bean
    public OpenAPI fileHubOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("file-hub API")
                        .version("1.0.0")
                        .description("分片上传 / 秒传 / 断点续传 / 完整性校验 / 管理员扫描修复"))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .description("POST /api/auth/login 返回的 token，填进 Authorize 即可")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}

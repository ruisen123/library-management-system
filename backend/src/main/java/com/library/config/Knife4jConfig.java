package com.library.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j（OpenAPI3）接口文档配置
 * 启动后访问：http://localhost:8080/doc.html
 */
@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().info(new Info()
                .title("LibMS 图书借阅管理系统 API")
                .description("基于 Spring Boot + MyBatis-Plus 的图书借阅管理系统接口文档")
                .version("1.0.0"));
    }
}

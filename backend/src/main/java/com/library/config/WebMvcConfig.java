package com.library.config;

import com.library.interceptor.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册登录拦截器 + 静态资源映射
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    /** 文件上传目录（映射为 /upload/** 静态资源） */
    @Value("${file.upload-dir}")
    private String uploadDir;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/auth/login",          // 登录接口放行
                        "/auth/register",       // 读者自助注册（公开接口，服务内按 IP 限流）
                        "/doc.html",            // Knife4j 文档页面
                        "/webjars/**",          // Knife4j 静态资源
                        "/v3/api-docs/**",      // OpenAPI 接口描述
                        "/swagger-ui/**",       // Swagger 页面
                        "/error",               // 错误页
                        // ⚠️ 预留：当前无文件上传功能，此豁免暂无风险；一旦启用上传，
                        // 必须为 /upload 补访问控制（图片直链场景 <img> 无法携带 token，需另设机制，如签名 URL）
                        "/upload/**"            // 上传文件访问
                );
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 将本地磁盘 uploadDir 映射到 /upload/**，用于图书封面等文件访问（预留，见上方豁免说明）
        registry.addResourceHandler("/upload/**")
                .addResourceLocations("file:" + uploadDir + "/");
    }
}

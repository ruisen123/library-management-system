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
                        // /upload/** 为封面图片公开访问路径（<img> 无法带 token）；仅允许图片类型、≤2MB、UUID 随机命名，
                        // normalize()+startsWith 防目录逃逸（BookController.java:113-140）；上传入口仍受 ADMIN/LIBRARIAN 角色保护
                        "/upload/**"            // 上传文件访问
                );
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 将本地磁盘 uploadDir 映射到 /upload/**，用于图书封面等文件访问（封面上传 2026-10-25 已启用，豁免口径见上方注释）
        registry.addResourceHandler("/upload/**")
                .addResourceLocations("file:" + uploadDir + "/");
    }
}

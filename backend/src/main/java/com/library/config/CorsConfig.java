package com.library.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * 跨域配置
 * 说明：开发阶段前端走 vite 代理本不会跨域，此处作为直连兜底。
 * 允许来源配置化（cors.allowed-origin，环境变量 CORS_ALLOWED_ORIGIN，逗号分隔多个）。
 * 默认同时放行 http://localhost:5173 与 http://127.0.0.1:5173：
 * vite 的 server.host 为 127.0.0.1 时控制台打印的是 127 地址，若只放行 localhost，
 * 浏览器经代理登录会被判定为非法来源（实测 403 Invalid CORS request）。
 * 生产直连场景请显式设置为前端域名。
 */
@Configuration
public class CorsConfig {

    /** 允许的跨域来源（从配置注入，默认开发端口） */
    @Value("${cors.allowed-origin}")
    private String allowedOrigin;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        // 支持逗号分隔的多个来源（如 localhost 与 127.0.0.1 并存），逐个注册
        for (String origin : allowedOrigin.split(",")) {
            String trimmed = origin.trim();
            if (!trimmed.isEmpty()) {
                config.addAllowedOriginPattern(trimmed);
            }
        }
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}

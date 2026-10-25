package com.library.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 接口文档面开关。
 *
 * 背景：knife4j / springdoc 的四组自带开关实测都无法关闭 /doc.html 与 /v3/api-docs
 * （静态资源由依赖内的 META-INF/resources 提供），此前生产关闭完全依赖 Nginx。
 * 直连 8080 时匿名者可读全部接口清单与字段结构，等于把攻击面地图递出去。
 * 本过滤器在应用层兜底：app.api-docs.enabled=false 时直接返回 404。
 */
@Component
@Order(Integer.MIN_VALUE + 2)
public class ApiDocsAccessFilter extends OncePerRequestFilter {

    private static final String[] DOC_PATHS = {
            "/doc.html", "/v3/api-docs", "/swagger-ui", "/webjars"
    };

    @Value("${app.api-docs.enabled:true}")
    private boolean enabled;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (enabled) {
            chain.doFilter(request, response);
            return;
        }
        String uri = request.getRequestURI();
        for (String path : DOC_PATHS) {
            if (uri.startsWith(path)) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":404,\"msg\":\"接口不存在\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}

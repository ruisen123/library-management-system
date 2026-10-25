package com.library.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求关联 ID（traceId）注入
 *
 * 背景：一次业务请求会产生多条 SQL 与业务日志，与并发请求的日志交错混排，排障时无法按请求聚合。
 * 本过滤器为每个请求生成（或透传调用方传入的）关联 ID 写入 MDC，日志 pattern 以 %X{requestId} 输出；
 * 同时回写同名响应头，便于前端/网关把一次交互的多条日志串起来。
 *
 * 注意：必须在 finally 中清理 MDC——线程池复用线程时若不清理会把上一个请求的 ID 带到下一个请求。
 */
@Component
@Order(Integer.MIN_VALUE + 1)
public class RequestIdFilter extends OncePerRequestFilter {

    /** 关联 ID 的请求/响应头名 */
    public static final String HEADER = "X-Request-Id";

    /** MDC 键名（须与 logback 配置中的 %X{requestId} 一致） */
    public static final String MDC_KEY = "requestId";

    /** 外部透传 ID 的最大长度，超出即视为不可信并重新生成 */
    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader(HEADER);
        if (requestId == null || requestId.isBlank() || requestId.length() > MAX_LENGTH
                || !requestId.matches("[A-Za-z0-9._:-]+")) {
            // 外部传入值不可信（长度与内容均需约束），非法时自行生成，避免污染日志
            requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}

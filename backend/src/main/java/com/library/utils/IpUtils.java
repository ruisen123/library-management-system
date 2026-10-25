package com.library.utils;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 请求 IP 工具：操作日志记录来源 IP 用
 *
 * 安全说明：X-Forwarded-For 由客户端可控，无条件信任可被伪造审计来源
 * （实测带 X-Forwarded-For: 9.9.9.9 时 operation_log.ip 被写成 9.9.9.9）。
 * 因此默认不信任该头，直接用 RemoteAddr；仅当部署在可信反向代理之后
 * （由代理负责覆盖 XFF）时，通过 app.trust-forwarded-header=true 显式开启。
 */
@Component
public class IpUtils {

    /** 是否信任 X-Forwarded-For（默认 false；生产置于 Nginx 之后时设为 true） */
    private static boolean trustForwardedHeader = false;

    @Value("${app.trust-forwarded-header:false}")
    public void setTrustForwardedHeader(boolean trust) {
        IpUtils.trustForwardedHeader = trust;
    }

    /** 获取当前请求来源 IP */
    public static String getClientIp() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        HttpServletRequest request = attrs.getRequest();
        String ip = null;
        if (trustForwardedHeader) {
            // 仅在可信代理场景读取：代理会把真实客户端 IP 写入首段
            ip = request.getHeader("X-Forwarded-For");
            if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
                ip = ip.split(",")[0].trim();
            } else {
                ip = null;
            }
        }
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        // 列宽保护：operation_log.ip 为 VARCHAR(50)，而 X-Forwarded-For 由客户端可控；
        // 超长值在 MySQL 严格模式下会抛错，且日志写入与借还等业务同事务，会连带业务回滚，故统一收敛到列宽内
        if (ip != null && ip.length() > 50) {
            ip = ip.substring(0, 50);
        }
        return ip;
    }
}
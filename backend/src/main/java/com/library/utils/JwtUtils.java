package com.library.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类：负责令牌的生成、解析与校验
 * 使用 jjwt 0.12.x 新 API
 */
@Component
public class JwtUtils {

    /** 签名密钥（从配置读取） */
    @Value("${jwt.secret}")
    private String secret;

    /** 令牌有效期（毫秒） */
    @Value("${jwt.expire}")
    private Long expire;

    /** 构建 HMAC 签名密钥 */
    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 启动期自检：HMAC 密钥须 ≥ 256 位（32 字节）、有效期须为正数毫秒。
     * 密钥过短：Keys.hmacShaKeyFor 会抛 WeakKeyException，但那是惰性抛错（要到首次登录才触发），
     * 且会被兜底异常处理器吞成"系统繁忙"，运维无从定位。此处前置校验并 fail-fast，报错即指向配置。
     * 有效期非法（如 JWT_EXPIRE=0/负数）：表现为"能登录但令牌立即失效"的半失效状态，同样前置校验（M23-2）。
     */
    @PostConstruct
    private void validateSecret() {
        int len = secret == null ? 0 : secret.getBytes(StandardCharsets.UTF_8).length;
        if (len < 32) {
            throw new IllegalStateException("JWT_SECRET 长度不足（当前 " + len
                    + " 字节），须 ≥ 32 字节（256 位），请检查环境变量 JWT_SECRET");
        }
        if (expire == null || expire <= 0) {
            throw new IllegalStateException("JWT_EXPIRE 配置非法（当前 " + expire
                    + "），必须为正整数毫秒，请检查环境变量 JWT_EXPIRE");
        }
    }

    /**
     * 生成令牌
     * @param userId   用户 ID
     * @param username 登录名
     * @param role     角色
     * @param pwdFingerprint 密码指纹（SHA256(密码哈希)，R19-6：改密后旧令牌自动失效）
     */
    public String generateToken(Long userId, String username, String role, String pwdFingerprint) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expire);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                .claim("pfp", pwdFingerprint)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(getKey())
                .compact();
    }

    /**
     * 解析令牌（校验签名与有效期），失败返回 null
     */
    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(getKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            // 签名错误 / 令牌过期 / 格式错误均视为无效
            return null;
        }
    }

    /** 从令牌解析用户 ID（subject 非数字等异常一律视为无效令牌，返回 null 交由拦截器按 401 处理） */
    public Long getUserId(String token) {
        Claims claims = parseToken(token);
        if (claims == null) {
            return null;
        }
        try {
            return Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 从令牌读取密码指纹（缺失/格式异常/令牌无效均返回 null，由拦截器按 401 处理）——R19-6 */
    public String getPwdFingerprint(String token) {
        Claims claims = parseToken(token);
        return claims == null ? null : claims.get("pfp", String.class);
    }
}

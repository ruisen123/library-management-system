package com.library.utils;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JWT 工具单元测试。
 * 用 ReflectionTestUtils 直接注入 secret / expire，不启动 Spring 容器。
 */
@DisplayName("JwtUtils：令牌生成、解析与启动期配置自检")
class JwtUtilsTest {

    /** 32 字节（256 位），满足 HMAC-SHA256 最低要求 */
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private JwtUtils jwt;

    @BeforeEach
    void setUp() {
        jwt = new JwtUtils();
        ReflectionTestUtils.setField(jwt, "secret", SECRET);
        ReflectionTestUtils.setField(jwt, "expire", 3_600_000L);
    }

    @Test
    @DisplayName("生成后能解析回原始声明（subject / username / role / 密码指纹）")
    void generateThenParse() {
        String token = jwt.generateToken(7L, "reader001", "READER", "pfp-abc");

        Claims claims = jwt.parseToken(token);
        assertNotNull(claims);
        assertEquals("7", claims.getSubject());
        assertEquals("reader001", claims.get("username", String.class));
        assertEquals("READER", claims.get("role", String.class));

        assertEquals(7L, jwt.getUserId(token));
        assertEquals("pfp-abc", jwt.getPwdFingerprint(token));
    }

    @Test
    @DisplayName("篡改令牌任意字符即校验失败（签名保护）")
    void tamperedTokenReturnsNull() {
        String token = jwt.generateToken(1L, "u", "READER", "p");
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertNull(jwt.parseToken(tampered));
        assertNull(jwt.getUserId(tampered));
        assertNull(jwt.getPwdFingerprint(tampered));
    }

    @Test
    @DisplayName("换一把密钥后旧令牌无效")
    void tokenInvalidUnderAnotherSecret() {
        String token = jwt.generateToken(1L, "u", "READER", "p");

        JwtUtils other = new JwtUtils();
        ReflectionTestUtils.setField(other, "secret", "ffffffffffffffffffffffffffffffff");
        ReflectionTestUtils.setField(other, "expire", 3_600_000L);

        assertNull(other.parseToken(token));
    }

    @Test
    @DisplayName("有效期已过（expire 为负）时令牌无效")
    void expiredTokenReturnsNull() {
        ReflectionTestUtils.setField(jwt, "expire", -1_000L);
        String token = jwt.generateToken(1L, "u", "READER", "p");
        assertNull(jwt.parseToken(token));
    }

    @Test
    @DisplayName("非法输入不抛异常，一律返回 null 交给拦截器按 401 处理")
    void garbageInputReturnsNull() {
        assertNull(jwt.parseToken("not-a-jwt"));
        assertNull(jwt.parseToken(""));
        assertNull(jwt.parseToken(null));
        assertNull(jwt.getUserId(null));
        assertNull(jwt.getPwdFingerprint(null));
    }

    @Test
    @DisplayName("启动自检：密钥短于 32 字节直接抛异常（fail-fast，指向配置）")
    void shortSecretFailsFast() {
        ReflectionTestUtils.setField(jwt, "secret", "too-short");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(jwt, "validateSecret"));
        assertTrue(e.getMessage().contains("JWT_SECRET"), "异常信息应指向 JWT_SECRET，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("启动自检：有效期为 0 或负数直接抛异常")
    void nonPositiveExpireFailsFast() {
        ReflectionTestUtils.setField(jwt, "expire", 0L);
        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(jwt, "validateSecret"));

        ReflectionTestUtils.setField(jwt, "expire", -5L);
        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(jwt, "validateSecret"));
    }

    @Test
    @DisplayName("合法配置下启动自检不抛异常")
    void validConfigPasses() {
        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(jwt, "validateSecret"));
    }
}

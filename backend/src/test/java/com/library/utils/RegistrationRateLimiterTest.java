package com.library.utils;

import com.library.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 注册限流器单元测试（纯内存滑动窗口，不启动 Spring、不连数据库）。
 */
@DisplayName("RegistrationRateLimiter：注册限流窗口")
class RegistrationRateLimiterTest {

    private RegistrationRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new RegistrationRateLimiter();
        ReflectionTestUtils.setField(limiter, "maxPerIp", 3);
        ReflectionTestUtils.setField(limiter, "windowMinutes", 60L);
        ReflectionTestUtils.setField(limiter, "globalMax", 100);
    }

    @Test
    @DisplayName("同一 IP 前 N 次放行，第 N+1 次抛业务异常")
    void perIpLimit() {
        for (int i = 0; i < 3; i++) {
            limiter.checkAndRecord("10.0.0.1");
        }
        BusinessException e = assertThrows(BusinessException.class,
                () -> limiter.checkAndRecord("10.0.0.1"));
        assertTrue(e.getMessage().contains("注册过于频繁"), "实际：" + e.getMessage());
    }

    @Test
    @DisplayName("不同 IP 各自计数，互不影响")
    void perIpIsolation() {
        limiter.checkAndRecord("10.0.0.1");
        limiter.checkAndRecord("10.0.0.1");
        limiter.checkAndRecord("10.0.0.1");
        assertDoesNotThrow(() -> limiter.checkAndRecord("10.0.0.2"));
    }

    @Test
    @DisplayName("全局上限生效：总量达上限后换 IP 也拒绝（防分布式灌号）")
    void globalLimit() {
        ReflectionTestUtils.setField(limiter, "globalMax", 2);
        limiter.checkAndRecord("1.1.1.1");
        limiter.checkAndRecord("2.2.2.2");
        BusinessException e = assertThrows(BusinessException.class,
                () -> limiter.checkAndRecord("3.3.3.3"));
        assertTrue(e.getMessage().contains("注册通道繁忙"), "实际：" + e.getMessage());
    }

    @Test
    @DisplayName("空 IP 归入 unknown 桶，不能靠不传 IP 绕过限制")
    void blankIpFallsIntoUnknownBucket() {
        ReflectionTestUtils.setField(limiter, "maxPerIp", 1);
        limiter.checkAndRecord(null);
        assertThrows(BusinessException.class, () -> limiter.checkAndRecord(""));
        assertThrows(BusinessException.class, () -> limiter.checkAndRecord("   "));
    }
}

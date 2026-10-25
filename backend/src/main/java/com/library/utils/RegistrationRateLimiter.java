package com.library.utils;

import com.library.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公开注册接口的进程内限流器。
 *
 * 公开注册是匿名写入口，若不限流，单机可被脚本批量灌入无效账号（占库/占用户名/刷审计）。
 * 本系统为单实例部署（见 deploy/部署说明.md），与登录防爆破一样使用进程内滑动窗口即可；
 * 多实例部署时应替换为 Redis 等共享计数（当前未引入，避免为小数据量系统增加外部依赖）。
 */
@Component
public class RegistrationRateLimiter {

    private final Map<String, Deque<Long>> perIpHits = new ConcurrentHashMap<>();
    private final Deque<Long> globalHits = new ArrayDeque<>();

    @Value("${app.register.max-per-ip:5}")
    private int maxPerIp;

    @Value("${app.register.window-minutes:60}")
    private long windowMinutes;

    @Value("${app.register.global-max:200}")
    private int globalMax;

    /**
     * 记录一次注册尝试；超限抛业务异常。
     * synchronized 保证"检查 + 计数写入"原子，避免并发请求同时越过阈值。
     */
    public synchronized void checkAndRecord(String ip) {
        long now = System.currentTimeMillis();
        long windowMs = windowMinutes * 60 * 1000L;
        prune(globalHits, now, windowMs);
        if (globalHits.size() >= globalMax) {
            throw new BusinessException("注册通道繁忙，请稍后再试");
        }
        String key = (ip == null || ip.isBlank()) ? "unknown" : ip;
        Deque<Long> hits = perIpHits.computeIfAbsent(key, k -> new ArrayDeque<>());
        prune(hits, now, windowMs);
        if (hits.size() >= maxPerIp) {
            throw new BusinessException("注册过于频繁，请 " + windowMinutes + " 分钟后再试");
        }
        hits.addLast(now);
        globalHits.addLast(now);
        // 防止大量不同来源 IP 使 Map 无界增长：窗口外条目在 prune 中已清空，空桶顺手移除
        perIpHits.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    private static void prune(Deque<Long> hits, long now, long windowMs) {
        while (!hits.isEmpty() && now - hits.peekFirst() > windowMs) {
            hits.pollFirst();
        }
    }
}

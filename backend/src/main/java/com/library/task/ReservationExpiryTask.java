package com.library.task;

import com.library.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 预约过期回收定时任务
 *
 * 每 5 分钟扫描一次"待取书且已过 7 天"的预约，置为已过期并把队首顺延给下一位。
 * 扫描失败只记应用日志，不影响业务请求（下次扫描会重试，状态条件更新天然幂等）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationExpiryTask {

    private final ReservationService reservationService;

    @Scheduled(
            fixedDelayString = "${app.reservation.expire-scan-ms:300000}",
            initialDelayString = "${app.reservation.expire-initial-delay-ms:60000}")
    public void scan() {
        try {
            int count = reservationService.expireOverdue();
            if (count > 0) {
                log.info("预约过期回收：本次回收 {} 条，已顺延队首", count);
            }
        } catch (Exception e) {
            log.error("预约过期回收失败，将在下一轮重试：{}", e.getMessage());
        }
    }
}

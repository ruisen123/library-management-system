package com.library.task;

import com.library.service.NotificationService;
import com.library.vo.NotificationScanVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 通知提醒扫描任务（每天 08:00，Asia/Shanghai）
 *
 * 生成到期/逾期/欠费/证将到期提醒，并清理超过保留期的已读通知。
 * 与预约回收任务分线程池执行，互不阻塞；失败只记应用日志，下一轮按 dedup_key 重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationReminderTask {

    private final NotificationService notificationService;

    @Scheduled(cron = "${app.notification.reminder-cron:0 0 8 * * ?}", zone = "Asia/Shanghai")
    public void scan() {
        try {
            NotificationScanVO vo = notificationService.scan();
            if (vo.getCreated() > 0 || vo.getPurged() > 0) {
                log.info("通知扫描：新增 {} 条提醒，清理 {} 条过期已读", vo.getCreated(), vo.getPurged());
            }
        } catch (Exception e) {
            log.error("通知扫描失败，将在下一轮重试：{}", e.getMessage());
        }
    }
}

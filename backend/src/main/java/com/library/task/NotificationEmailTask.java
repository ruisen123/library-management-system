package com.library.task;

import com.library.service.NotificationEmailService;
import com.library.vo.EmailDispatchVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 通知邮件投递任务（默认每 60 秒一轮；未启用时 dispatch() 直接返回，不产生任何外呼）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEmailTask {

    private final NotificationEmailService emailService;

    @Scheduled(fixedDelayString = "${app.notification.email.scan-ms:60000}",
            initialDelayString = "${app.notification.email.initial-delay-ms:30000}")
    public void dispatch() {
        try {
            EmailDispatchVO vo = emailService.dispatch();
            if (vo.getSent() + vo.getFailed() > 0) {
                log.info("通知邮件投递：成功 {} 封，失败待重试 {} 封，跳过 {} 封",
                        vo.getSent(), vo.getFailed(), vo.getSkipped());
            }
        } catch (Exception e) {
            log.error("通知邮件投递异常，将在下一轮重试：{}", e.getMessage());
        }
    }
}

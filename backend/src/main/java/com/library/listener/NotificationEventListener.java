package com.library.listener;

import com.library.event.NotificationEvent;
import com.library.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 通知事件监听器
 *
 * AFTER_COMMIT：业务事务提交后才落通知，通知写失败不回滚借还/注册；
 * fallbackExecution=true：无事务上下文（如定时任务内部推进队首）时立即执行。
 * 漏写由 NotificationService.scan() 按 dedup_key 幂等补齐。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private final NotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNotificationEvent(NotificationEvent event) {
        try {
            notificationService.create(event.userId(), event.type(), event.title(), event.content(),
                    event.bizType(), event.bizId(), event.dedupKey());
        } catch (Exception e) {
            log.warn("站内通知落库失败（将由扫描补齐）dedupKey={}, err={}", event.dedupKey(), e.getMessage());
        }
    }
}

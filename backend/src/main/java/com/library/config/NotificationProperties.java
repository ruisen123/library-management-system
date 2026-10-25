package com.library.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 通知与邮件通道配置（app.notification.*）
 *
 * 邮件默认关闭：断网/无 SMTP 凭据时站内通知照常可用，与"审计旁路不阻塞业务"的口径一致。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.notification")
public class NotificationProperties {

    /** 到期前多少天提醒 */
    private int dueSoonDays = 3;
    /** 借阅证到期前多少天提醒 */
    private int cardExpiringDays = 30;
    /** 已读通知保留天数（超期物理清理） */
    private int retentionDays = 180;

    private Email email = new Email();

    @Data
    public static class Email {
        /** 是否启用邮件通道 */
        private boolean enabled = false;
        /** 发件人 */
        private String from = "no-reply@libms.local";
        /** 最大尝试次数 */
        private int maxAttempts = 5;
        /** 单轮发送批量上限 */
        private int batchSize = 50;
        /** 用户已在站内读过则跳过邮件 */
        private boolean skipIfRead = true;
    }
}

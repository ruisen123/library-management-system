package com.library.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.library.common.constant.NotificationConstant;
import com.library.config.NotificationProperties;
import com.library.entity.Notification;
import com.library.entity.SysUser;
import com.library.mapper.NotificationMapper;
import com.library.mapper.SysUserMapper;
import com.library.service.NotificationEmailService;
import com.library.vo.EmailDispatchVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知邮件投递实现
 *
 * 可靠性口径（工程实现，不包装成创新点）：
 * 1. 认领式发送：先条件更新为"发送中"，抢到才发，避免定时任务与手动扫描重复投递；
 * 2. 网络 I/O 一律在数据库事务之外，发送慢/SMTP 不可达不会拖住业务连接；
 * 3. 失败按退避重试，超过上限置为"失败"；进程崩溃残留的"发送中"由超时回收兜底；
 * 4. 语义为"至少一次"：极端情况下（已发出但回写前崩溃）可能重复一封，正文不含敏感信息。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationEmailServiceImpl implements NotificationEmailService {

    /** 退避间隔（分钟），第 n 次失败后取第 n 个值 */
    private static final int[] BACKOFF_MINUTES = {1, 5, 30, 120, 360};
    /** "发送中"超过该分钟数视为进程遗留，回收重投 */
    private static final int SENDING_LEASE_MINUTES = 10;

    private final NotificationMapper notificationMapper;
    private final SysUserMapper sysUserMapper;
    private final NotificationProperties properties;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Override
    public EmailDispatchVO dispatch() {
        EmailDispatchVO result = new EmailDispatchVO();
        NotificationProperties.Email cfg = properties.getEmail();
        if (!cfg.isEnabled()) {
            return result;
        }
        recoverStaleSending();

        List<Notification> pending = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .in(Notification::getEmailStatus, NotificationConstant.EMAIL_PENDING, NotificationConstant.EMAIL_RETRY)
                .and(w -> w.isNull(Notification::getNextAttemptTime)
                        .or().le(Notification::getNextAttemptTime, LocalDateTime.now()))
                .lt(Notification::getEmailAttempts, cfg.getMaxAttempts())
                .orderByAsc(Notification::getId)
                .last("LIMIT " + Math.max(cfg.getBatchSize(), 1)));

        for (Notification n : pending) {
            // 认领：只有把状态从 0/2 抢到 5 的那一次才真正发送
            int claimed = notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                    .eq(Notification::getId, n.getId())
                    .in(Notification::getEmailStatus,
                            NotificationConstant.EMAIL_PENDING, NotificationConstant.EMAIL_RETRY)
                    .set(Notification::getEmailStatus, NotificationConstant.EMAIL_SENDING));
            if (claimed == 0) {
                continue;
            }
            sendOne(n, cfg, result);
        }
        return result;
    }

    private void sendOne(Notification n, NotificationProperties.Email cfg, EmailDispatchVO result) {
        SysUser user = sysUserMapper.selectById(n.getUserId());
        if (user == null || StrUtil.isBlank(user.getEmail())
                || Integer.valueOf(0).equals(user.getEmailNotify())) {
            markSkipped(n.getId());
            result.setSkipped(result.getSkipped() + 1);
            return;
        }
        if (cfg.isSkipIfRead() && NotificationConstant.READ_YES.equals(n.getReadFlag())) {
            // 用户已在站内读过，不再重复发邮件
            markSkipped(n.getId());
            result.setSkipped(result.getSkipped() + 1);
            return;
        }
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            markFailure(n, "邮件服务未配置（缺少 JavaMailSender）");
            result.setFailed(result.getFailed() + 1);
            return;
        }
        try {
            var message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(cfg.getFrom());
            helper.setTo(user.getEmail());
            helper.setSubject("[LibMS] " + StrUtil.blankToDefault(n.getTitle(), "系统通知"));
            helper.setText(StrUtil.blankToDefault(n.getContent(), ""), false);
            sender.send(message);
            notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                    .eq(Notification::getId, n.getId())
                    .set(Notification::getEmailStatus, NotificationConstant.EMAIL_SENT)
                    .set(Notification::getEmailSentTime, LocalDateTime.now())
                    .set(Notification::getEmailError, null));
            result.setSent(result.getSent() + 1);
        } catch (Exception e) {
            log.warn("通知邮件发送失败 id={} err={}", n.getId(), e.getMessage());
            markFailure(n, e.getMessage());
            result.setFailed(result.getFailed() + 1);
        }
    }

    private void markSkipped(Long id) {
        notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, id)
                .eq(Notification::getEmailStatus, NotificationConstant.EMAIL_SENDING)
                .set(Notification::getEmailStatus, NotificationConstant.EMAIL_SKIPPED));
    }

    private void markFailure(Notification n, String error) {
        int attempts = (n.getEmailAttempts() == null ? 0 : n.getEmailAttempts()) + 1;
        int max = Math.max(properties.getEmail().getMaxAttempts(), 1);
        int slot = Math.min(attempts, BACKOFF_MINUTES.length) - 1;
        notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, n.getId())
                .eq(Notification::getEmailStatus, NotificationConstant.EMAIL_SENDING)
                .set(Notification::getEmailAttempts, attempts)
                .set(Notification::getEmailStatus,
                        attempts >= max ? NotificationConstant.EMAIL_FAILED : NotificationConstant.EMAIL_RETRY)
                .set(Notification::getNextAttemptTime,
                        attempts >= max ? null : LocalDateTime.now().plusMinutes(BACKOFF_MINUTES[Math.max(slot, 0)]))
                .set(Notification::getEmailError, StrUtil.maxLength(StrUtil.blankToDefault(error, "未知错误"), 500)));
    }

    private void recoverStaleSending() {
        int recovered = notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getEmailStatus, NotificationConstant.EMAIL_SENDING)
                .lt(Notification::getUpdateTime, LocalDateTime.now().minusMinutes(SENDING_LEASE_MINUTES))
                .set(Notification::getEmailStatus, NotificationConstant.EMAIL_RETRY));
        if (recovered > 0) {
            log.warn("回收 {} 条超时未回写的邮件（发送中 → 重试）", recovered);
        }
    }
}

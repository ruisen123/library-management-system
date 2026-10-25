package com.library.service;

import com.library.vo.EmailDispatchVO;

/**
 * 通知邮件投递服务（notification 表兼作 outbox）
 */
public interface NotificationEmailService {

    /** 投递一批待发/待重试的邮件（认领 → 发送 → 回写状态，网络 I/O 不在事务内） */
    EmailDispatchVO dispatch();
}

package com.library.common.constant;

/**
 * 站内通知与邮件投递常量（对应表 notification）
 */
public interface NotificationConstant {

    // 通知类型
    Integer TYPE_RESERVATION_READY = 1; // 预约到书
    Integer TYPE_DUE_SOON = 2;          // 临近到期
    Integer TYPE_OVERDUE = 3;           // 逾期催还
    Integer TYPE_FINE_UNPAID = 4;       // 未缴罚金
    Integer TYPE_CARD_EXPIRING = 5;     // 借阅证将到期
    Integer TYPE_WELCOME = 6;           // 注册欢迎

    // 已读标记
    Integer READ_NO = 0;
    Integer READ_YES = 1;

    // 邮件投递状态（表本身兼作 outbox）
    Integer EMAIL_PENDING = 0;  // 待发
    Integer EMAIL_SENT = 1;     // 已发
    Integer EMAIL_RETRY = 2;    // 失败待重试
    Integer EMAIL_SKIPPED = 3;  // 跳过（未启用/无邮箱/已读）
    Integer EMAIL_FAILED = 4;   // 超过最大尝试次数
    Integer EMAIL_SENDING = 5;  // 发送中（已认领）

    // 业务类型（前端跳转依据）
    String BIZ_RESERVATION = "RESERVATION";
    String BIZ_BORROW = "BORROW";
    String BIZ_FINE = "FINE";
    String BIZ_USER = "USER";
    String BIZ_SYSTEM = "SYSTEM";
}

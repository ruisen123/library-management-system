package com.library.service;

import com.library.vo.NotificationPageVO;
import com.library.vo.NotificationScanVO;

/**
 * 站内通知服务
 */
public interface NotificationService {

    /** 本人通知分页（附带未读数） */
    NotificationPageVO pageForUser(Long userId, int page, int size, boolean unreadOnly);

    /** 本人未读数 */
    long unreadCount(Long userId);

    /** 标记单条已读（非本人/不存在 → 404） */
    void markRead(Long userId, Long id);

    /** 全部已读，返回本次更新条数 */
    int markAllRead(Long userId);

    /** 幂等创建通知（dedupKey 重复时静默跳过） */
    boolean create(Long userId, Integer type, String title, String content,
                   String bizType, Long bizId, String dedupKey);

    /** 扫描生成提醒类通知 + 清理过期已读（管理员手动触发与定时任务共用） */
    NotificationScanVO scan();
}

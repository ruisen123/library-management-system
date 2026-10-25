package com.library.vo;

import com.library.entity.Notification;
import lombok.Data;

import java.util.List;

/**
 * 站内通知分页结果（附带未读数，供顶栏红点与列表页共用一次请求）
 */
@Data
public class NotificationPageVO {

    private List<Notification> records;
    private long total;
    private long unreadCount;
    private long page;
    private long size;
}

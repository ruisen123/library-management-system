package com.library.vo;

import lombok.Data;

/**
 * 手动触发通知扫描（管理员）的执行结果
 */
@Data
public class NotificationScanVO {

    /** 本次新增通知数 */
    private int created;
    /** 本次清理的过期已读通知数 */
    private int purged;
    /** 本次邮件发送成功数 */
    private int emailed;
    /** 本次邮件失败（待重试）数 */
    private int failed;
    /** 本次跳过邮件数（未启用/无邮箱/已读） */
    private int skipped;
    /** 是否因上一轮仍在执行而被跳过 */
    private boolean busy;
}

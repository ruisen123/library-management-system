package com.library.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内通知实体（对应表 notification）
 *
 * 与 reservation 同为扩展表，不设逻辑删除列：已读旧消息由保留期任务物理清理，
 * 因此"5 张表有 deleted"的巡检与答辩口径保持不变。
 */
@Data
@TableName("notification")
public class Notification {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接收读者 ID */
    private Long userId;

    /** 类型：1预约到书 2临近到期 3逾期 4未缴罚金 5借阅证将到期 6欢迎 */
    private Integer type;

    /** 标题 */
    private String title;

    /** 正文（创建时快照，业务对象后续变化不回改） */
    private String content;

    /** 业务类型：RESERVATION/BORROW/FINE/USER/SYSTEM */
    private String bizType;

    /** 业务主键（前端跳转依据） */
    private Long bizId;

    /** 幂等去重键（同一业务事件只落一条） */
    private String dedupKey;

    /** 已读：0未读 1已读 */
    private Integer readFlag;

    /** 已读时间 */
    private LocalDateTime readTime;

    /** 邮件：0待发 1已发 2重试 3跳过 4失败 5发送中 */
    private Integer emailStatus;

    /** 邮件尝试次数 */
    private Integer emailAttempts;

    /** 下次邮件尝试时间 */
    private LocalDateTime nextAttemptTime;

    /** 邮件发送成功时间 */
    private LocalDateTime emailSentTime;

    /** 最近一次邮件错误（截断保存） */
    private String emailError;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}

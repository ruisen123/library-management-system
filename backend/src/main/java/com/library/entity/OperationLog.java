package com.library.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志实体（对应表 operation_log）
 */
@Data
@TableName("operation_log")
public class OperationLog {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作人外键（无登录场景为 null） */
    private Long userId;

    /** 操作类型（如 LOGIN / BORROW / RETURN / RENEW） */
    private String operation;

    /** 操作详情 */
    private String detail;

    /** 操作来源 IP */
    private String ip;

    /** 操作时间（只增不改） */
    private LocalDateTime createTime;

    /** 操作人姓名（联表查询填充） */
    @TableField(exist = false)
    private String userName;
}

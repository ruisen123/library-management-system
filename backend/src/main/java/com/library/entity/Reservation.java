package com.library.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 预约排队实体（对应表 reservation）
 */
@Data
@TableName("reservation")
public class Reservation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 预约图书外键 */
    private Long bookId;

    /** 预约读者外键 */
    private Long userId;

    /** 状态：1排队中 2待取书 3已完成 4已取消 5已过期 */
    private Integer status;

    /** 该书预约序号（申请时递增分配） */
    private Integer queueNo;

    /** 到书时间 */
    private LocalDateTime readyTime;

    /** 待取截止时间 */
    private LocalDateTime expireTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    // ===== 展示字段（非表字段） =====

    @TableField(exist = false)
    private String bookTitle;

    @TableField(exist = false)
    private String userName;

    @TableField(exist = false)
    private String readerNo;

    /** 当前排队位置（1 表示队首；待取书固定为 1） */
    @TableField(exist = false)
    private Integer queuePosition;
}

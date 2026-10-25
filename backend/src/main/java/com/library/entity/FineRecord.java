package com.library.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 罚金记录实体（对应表 fine_record）
 */
@Data
@TableName("fine_record")
public class FineRecord {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 应缴读者外键 */
    private Long userId;

    /** 关联借阅记录外键 */
    private Long borrowId;

    /** 应缴金额（元） */
    private BigDecimal amount;

    /** 类型：1逾期罚金 2破损赔偿 3遗失赔偿 */
    private Integer type;

    /** 缴纳状态：1未缴 2已缴 */
    private Integer status;

    /** 实际缴纳时间 */
    private LocalDateTime paidTime;

    /** 备注（赔偿原因、罚金天数说明等） */
    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    // ===== 联表查询的冗余展示字段 =====

    /** 读者姓名 */
    @TableField(exist = false)
    private String userName;

    /** 读者证号 */
    @TableField(exist = false)
    private String readerNo;

    /** 图书名称 */
    @TableField(exist = false)
    private String bookTitle;
}

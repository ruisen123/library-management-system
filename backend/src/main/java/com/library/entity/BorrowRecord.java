package com.library.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 借阅记录实体（对应表 borrow_record）
 */
@Data
@TableName("borrow_record")
public class BorrowRecord {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 借阅读者外键 */
    private Long userId;

    /** 图书外键 */
    private Long bookId;

    /** 借出日期 */
    private LocalDate borrowDate;

    /** 应还日期（借出日 + 30 天，续借再 +30 天） */
    private LocalDate dueDate;

    /** 实际归还日期（null 表示未归还） */
    private LocalDate returnDate;

    /** 状态：1在借 2已还 3已赔偿 */
    private Integer status;

    /** 已续借次数（0 或 1，每册限续借一次） */
    private Integer renewCount;

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

package com.library.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 图书实体（对应表 book）
 */
@Data
@TableName("book")
public class Book {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** ISBN 书号（同书多册合并为一条记录） */
    private String isbn;

    /** 书名 */
    private String title;

    /** 作者（多人以逗号分隔） */
    private String author;

    /** 分类外键 */
    private Long categoryId;

    /** 出版社外键 */
    private Long publisherId;

    /** 出版日期 */
    private LocalDate publishDate;

    /** 定价（破损/遗失赔偿基准） */
    private BigDecimal price;

    /** 馆藏总册数 */
    private Integer totalStock;

    /** 当前可借册数（借出减1，归还加1） */
    private Integer stock;

    /** 累计借阅次数（借出时加1，热度排行用） */
    private Integer borrowCount;

    /** 馆藏位置（书架号） */
    private String location;

    /** 封面图片地址 */
    private String cover;

    /** 内容简介 */
    private String description;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 逻辑删除：0未删 1已删（在借中的图书禁止删除） */
    @TableLogic
    private Integer deleted;

    // ===== 以下为联表查询的冗余展示字段，非表字段 =====

    /** 分类名称（联表查询填充） */
    @TableField(exist = false)
    private String categoryName;

    /** 出版社名称（联表查询填充） */
    @TableField(exist = false)
    private String publisherName;

    /** 已被"待取书"预约占住的副本数（联表统计填充，非表字段；前端用 stock - 它 展示真实可借） */
    @TableField(exist = false)
    private Integer reservedCount;
}

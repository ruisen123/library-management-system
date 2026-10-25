package com.library.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 公告实体（对应表 notice）
 */
@Data
@TableName("notice")
public class Notice {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 公告标题 */
    private String title;

    /** 公告正文 */
    private String content;

    /** 发布管理员外键 */
    private Long publisherId;

    /** 状态：1已发布 0已撤回 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 逻辑删除：0未删 1已删 */
    @TableLogic
    private Integer deleted;

    /** 发布人姓名（联表查询填充） */
    @TableField(exist = false)
    private String publisherName;
}

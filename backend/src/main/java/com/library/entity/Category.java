package com.library.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 图书分类实体（对应表 category）
 */
@Data
@TableName("category")
public class Category {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 分类名称（唯一） */
    private String name;

    /** 显示排序号（越小越靠前） */
    private Integer sort;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 逻辑删除：0未删 1已删 */
    @TableLogic
    private Integer deleted;
}

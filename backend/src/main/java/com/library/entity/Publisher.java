package com.library.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 出版社实体（对应表 publisher）
 */
@Data
@TableName("publisher")
public class Publisher {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 出版社名称（唯一） */
    private String name;

    /** 联系方式 */
    private String contact;

    /** 出版社地址 */
    private String address;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 逻辑删除：0未删 1已删 */
    @TableLogic
    private Integer deleted;
}

package com.library.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户实体（对应表 sys_user：读者与管理员统一账号）
 */
@Data
@TableName("sys_user")
public class SysUser {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名（唯一） */
    private String username;

    /** 密码（BCrypt 加密存储，序列化时忽略，禁止返回前端） */
    @JsonIgnore
    private String password;

    /** 真实姓名 */
    private String realName;

    /** 角色：ADMIN 系统管理员 / LIBRARIAN 图书管理员 / READER 读者 */
    private String role;

    /** 读者证号（仅读者角色填写，唯一） */
    private String readerNo;

    /** 性别：0未知 1男 2女 */
    private Integer gender;

    /** 联系电话 */
    private String phone;

    /** 账号状态：1正常 0冻结 */
    private Integer status;

    /** 当前在借册数（冗余字段，借书上限校验用） */
    private Integer borrowedCount;

    /** 可借上限（默认 5，按读者覆盖；0 表示不可借） */
    private Integer borrowLimit;

    /** 允许的最大未缴罚金（0 表示有欠费即禁借） */
    private BigDecimal maxUnpaid;

    /** 借阅证有效期（NULL 表示长期有效） */
    private LocalDate validUntil;

    /** 通知邮箱（可空，不用于登录） */
    private String email;

    /** 接收邮件通知：1是 0否 */
    private Integer emailNotify;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 逻辑删除：0未删 1已删 */
    @TableLogic
    private Integer deleted;
}

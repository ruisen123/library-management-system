package com.library.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 用户新增/编辑请求参数
 */
@Data
public class UserDTO {

    /** 主键（编辑时必传，新增时为空） */
    private Long id;

    /** 登录名（新增时必填，编辑时不更新该字段，故不做 @NotBlank 校验，由 Service 层按场景校验） */
    @Size(max = 30, message = "登录名不能超过 30 个字符")
    private String username;

    /** 密码（仅新增时使用，编辑/重置密码走单独接口） */
    private String password;

    /** 真实姓名 */
    @NotBlank(message = "姓名不能为空")
    // 长度口径说明：@Size 按 UTF-16 码元计数（emoji 等增补平面字符占 2 个码元），
    // 严于 MySQL VARCHAR(30) 的代码点计数——故含 emoji 的姓名可能被"过度拒绝"，属已知且可接受的口径差异
    @Size(max = 30, message = "姓名长度不能超过 30 个字符")
    private String realName;

    /** 角色：ADMIN / LIBRARIAN / READER */
    @NotBlank(message = "角色不能为空")
    private String role;

    /** 读者证号（仅读者角色） */
    @Size(max = 30, message = "读者证号不能超过 30 个字符")
    private String readerNo;

    /** 性别：0未知 1男 2女 */
    @Min(value = 0, message = "性别取值不合法")
    @Max(value = 2, message = "性别取值不合法")
    private Integer gender;

    /** 联系电话 */
    @Size(max = 20, message = "联系电话长度不能超过 20 位")
    private String phone;

    /** 通知邮箱（可空；不用于登录） */
    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱长度不能超过 100 个字符")
    private String email;

    /** 可借上限（默认 5，管理员按读者调整；0 表示不可借） */
    @Min(value = 0, message = "可借上限不能小于 0")
    @Max(value = 50, message = "可借上限不能超过 50")
    private Integer borrowLimit;

    /** 允许的最大未缴罚金（0 表示有欠费即禁借） */
    @DecimalMin(value = "0.00", message = "最大欠费额不能为负数")
    @Digits(integer = 6, fraction = 2, message = "最大欠费额最多 6 位整数、2 位小数")
    private BigDecimal maxUnpaid;

    /** 借阅证有效期（NULL 表示长期有效；允许填过去日期用于停用借阅） */
    private LocalDate validUntil;

    /** 接收邮件通知：1是 0否 */
    @Min(value = 0, message = "邮件通知开关取值不合法")
    @Max(value = 1, message = "邮件通知开关取值不合法")
    private Integer emailNotify;
}

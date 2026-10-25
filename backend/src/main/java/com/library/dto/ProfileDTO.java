package com.library.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 个人资料修改请求参数
 */
@Data
public class ProfileDTO {

    /** 真实姓名 */
    @NotBlank(message = "姓名不能为空")
    @Size(max = 30, message = "姓名长度不能超过 30 个字符")
    private String realName;

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

    /** 接收邮件通知：1是 0否 */
    @Min(value = 0, message = "邮件通知开关取值不合法")
    @Max(value = 1, message = "邮件通知开关取值不合法")
    private Integer emailNotify;
}
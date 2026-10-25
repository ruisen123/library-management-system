package com.library.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 读者自助注册请求参数
 *
 * 与管理员开户（UserDTO）的区别：角色固定为 READER、读者证号由系统生成，
 * 因此不接收 role/readerNo，避免公开接口被用来创建管理员账号。
 */
@Data
public class RegisterDTO {

    @NotBlank(message = "登录名不能为空")
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{2,29}$",
            message = "登录名需以字母开头，仅含字母、数字、下划线，长度 3-30 位")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,30}$",
            message = "密码需 8-30 位，且同时包含字母和数字")
    private String password;

    @NotBlank(message = "姓名不能为空")
    @Size(max = 30, message = "姓名长度不能超过 30 个字符")
    private String realName;

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @Min(value = 0, message = "性别取值不合法")
    @Max(value = 2, message = "性别取值不合法")
    private Integer gender;

    /** 通知邮箱（可选；不用于登录） */
    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱长度不能超过 100 个字符")
    private String email;
}

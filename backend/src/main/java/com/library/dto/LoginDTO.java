package com.library.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 登录请求参数
 */
@Data
public class LoginDTO {

    /** 登录名（长度与 sys_user.username 列宽一致，避免超长值进入失败计数缓存） */
    @NotBlank(message = "登录名不能为空")
    @Size(max = 30, message = "登录名长度不能超过 30 个字符")
    private String username;

    /** 密码 */
    @NotBlank(message = "密码不能为空")
    private String password;
}

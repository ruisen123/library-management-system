package com.library.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前登录用户信息（存入 ThreadLocal，供业务层获取）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser {
    /** 用户主键 */
    private Long id;
    /** 登录名 */
    private String username;
    /** 真实姓名 */
    private String realName;
    /** 角色：ADMIN / LIBRARIAN / READER */
    private String role;
}

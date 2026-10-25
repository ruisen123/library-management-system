package com.library.vo;

import com.library.entity.SysUser;
import lombok.Data;

/**
 * 登录响应对象
 */
@Data
public class LoginVO {

    /** JWT 令牌 */
    private String token;

    /** 用户信息 */
    private SysUser user;
}

package com.library.service;

import com.library.dto.LoginDTO;
import com.library.dto.RegisterDTO;
import com.library.vo.LoginVO;

/**
 * 认证服务接口
 */
public interface AuthService {

    /**
     * 登录：校验账号密码与状态，签发 JWT
     */
    LoginVO login(LoginDTO dto);

    /**
     * 读者自助注册：公开接口，固定 READER 角色，成功后直接签发令牌
     */
    LoginVO register(RegisterDTO dto);
}

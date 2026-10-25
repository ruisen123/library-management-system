package com.library.controller;

import com.library.common.Result;
import com.library.dto.LoginDTO;
import com.library.dto.RegisterDTO;
import com.library.service.AuthService;
import com.library.vo.LoginVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证控制器
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "01-认证模块")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    @Operation(summary = "登录")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.ok("登录成功", authService.login(dto));
    }

    @PostMapping("/register")
    @Operation(summary = "读者自助注册（公开接口，注册即登录）")
    public Result<LoginVO> register(@Valid @RequestBody RegisterDTO dto) {
        return Result.ok("注册成功，已为您自动登录", authService.register(dto));
    }
}

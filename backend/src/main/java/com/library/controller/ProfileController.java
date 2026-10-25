package com.library.controller;

import com.library.annotation.RequireRole;
import com.library.common.Result;
import com.library.common.constant.RoleConstant;
import com.library.dto.PasswordDTO;
import com.library.dto.ProfileDTO;
import com.library.entity.SysUser;
import com.library.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 个人中心控制器（所有已登录角色可访问）
 */
@RestController
@RequestMapping("/profile")
@RequiredArgsConstructor
@RequireRole({RoleConstant.ADMIN, RoleConstant.LIBRARIAN, RoleConstant.READER})
@Tag(name = "10-个人中心模块")
public class ProfileController {

    private final UserService userService;

    @GetMapping
    @Operation(summary = "查询当前登录用户信息")
    public Result<SysUser> profile() {
        return Result.ok(userService.profile());
    }

    @PutMapping
    @Operation(summary = "修改个人资料")
    public Result<Void> updateProfile(@Valid @RequestBody ProfileDTO dto) {
        userService.updateProfile(dto);
        return Result.ok("保存成功", null);
    }

    @PutMapping("/password")
    @Operation(summary = "修改密码")
    public Result<Void> updatePassword(@Valid @RequestBody PasswordDTO dto) {
        userService.updatePassword(dto);
        return Result.ok("密码修改成功", null);
    }
}
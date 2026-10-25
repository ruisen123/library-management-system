package com.library.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.library.annotation.RequireRole;
import com.library.common.Result;
import com.library.common.constant.RoleConstant;
import com.library.dto.UserDTO;
import com.library.entity.SysUser;
import com.library.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 用户管理控制器（读者 + 管理员账号）
 * 整个模块仅系统管理员可访问
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@RequireRole(RoleConstant.ADMIN)
@Tag(name = "02-用户管理模块")
public class UserController {

    private final UserService userService;

    @GetMapping
    @Operation(summary = "分页查询用户")
    public Result<IPage<SysUser>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status) {
        // 分页参数归一化：MyBatis-Plus 在 size<1 时会跳过分页返回全表，此处强制下界为 1
        page = Math.max(page, 1);
        size = Math.max(size, 1);
        return Result.ok(userService.page(page, size, role, keyword, status));
    }

    @PostMapping
    @Operation(summary = "新增用户")
    public Result<Void> add(@Valid @RequestBody UserDTO dto) {
        userService.add(dto);
        return Result.ok();
    }

    @PutMapping("/{id}")
    @Operation(summary = "编辑用户")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody UserDTO dto) {
        dto.setId(id);
        userService.update(dto);
        return Result.ok();
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "启用/冻结切换")
    public Result<Void> toggleStatus(@PathVariable Long id) {
        userService.toggleStatus(id);
        return Result.ok();
    }

    @PutMapping("/{id}/password")
    @Operation(summary = "重置密码")
    public Result<Void> resetPassword(@PathVariable Long id) {
        userService.resetPassword(id);
        return Result.ok();
    }

    /**
     * 删除用户（逻辑删除，带自删除/在借/欠费三重护栏）。
     * 说明：本端点为 API 级能力（自动化测试与运维使用）；管理界面按 docs/03 用例清单
     * 以「冻结/启用」替代删除，未提供 UI 入口——前端 api/user.js 中的 deleteUser 封装同理。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除用户")
    public Result<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return Result.ok();
    }
}

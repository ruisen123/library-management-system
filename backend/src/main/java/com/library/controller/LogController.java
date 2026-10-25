package com.library.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.library.annotation.RequireRole;
import com.library.common.Result;
import com.library.common.constant.RoleConstant;
import com.library.entity.OperationLog;
import com.library.service.OperationLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作日志控制器（仅系统管理员）
 */
@RestController
@RequestMapping("/logs")
@RequiredArgsConstructor
@RequireRole(RoleConstant.ADMIN)
@Tag(name = "09-操作日志模块")
public class LogController {

    private final OperationLogService operationLogService;

    @GetMapping
    @Operation(summary = "分页查询操作日志")
    public Result<IPage<OperationLog>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword) {
        // 分页参数归一化：MyBatis-Plus 在 size<1 时会跳过分页返回全表，此处强制下界为 1
        page = Math.max(page, 1);
        size = Math.max(size, 1);
        return Result.ok(operationLogService.page(page, size, keyword));
    }
}
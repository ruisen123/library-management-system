package com.library.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.library.annotation.RequireRole;
import com.library.common.Result;
import com.library.common.constant.RoleConstant;
import com.library.dto.NoticeDTO;
import com.library.entity.Notice;
import com.library.service.NoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 公告控制器
 * 查询：所有已登录用户；发布/撤回/删除：仅系统管理员
 */
@RestController
@RequestMapping("/notices")
@RequiredArgsConstructor
@Tag(name = "07-公告模块")
public class NoticeController {

    private final NoticeService noticeService;

    @GetMapping
    @RequireRole({RoleConstant.ADMIN, RoleConstant.LIBRARIAN, RoleConstant.READER})
    @Operation(summary = "分页查询公告")
    public Result<IPage<Notice>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Boolean onlyPublished) {
        // 分页参数归一化：MyBatis-Plus 在 size<1 时会跳过分页返回全表，此处强制下界为 1
        page = Math.max(page, 1);
        size = Math.max(size, 1);
        return Result.ok(noticeService.page(page, size, status, onlyPublished));
    }

    @PostMapping
    // 2026-10-22：公告发布放开给馆员 —— 闭馆通知/新书到馆/活动预告属馆员日常事务，
    // publisherId 取当前登录人（NoticeServiceImpl:56）且写操作日志（:59），责任可追溯。
    // 编辑/撤回/删除仍限管理员：Service 未校验发布者，放开会允许馆员改动他人公告。
    @RequireRole({RoleConstant.ADMIN, RoleConstant.LIBRARIAN})
    @Operation(summary = "发布公告")
    public Result<Void> add(@Valid @RequestBody NoticeDTO dto) {
        noticeService.add(dto);
        return Result.ok("发布成功", null);
    }

    @PutMapping("/{id}")
    @RequireRole(RoleConstant.ADMIN)
    @Operation(summary = "编辑公告 / 发布切换")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody NoticeDTO dto) {
        noticeService.update(id, dto);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @RequireRole(RoleConstant.ADMIN)
    @Operation(summary = "删除公告")
    public Result<Void> delete(@PathVariable Long id) {
        noticeService.delete(id);
        return Result.ok();
    }
}
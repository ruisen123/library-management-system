package com.library.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.library.annotation.RequireRole;
import com.library.common.Result;
import com.library.common.constant.ReservationConstant;
import com.library.common.constant.ReservationStatusConstant;
import com.library.common.constant.RoleConstant;
import com.library.dto.ReservationDTO;
import com.library.entity.Reservation;
import com.library.service.OperationLogService;
import com.library.service.ReservationService;
import com.library.utils.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 预约排队控制器
 * 读者：预约、取消本人预约、查看本人预约；
 * 馆员/管理员：查看全量预约队列、手动回收过期预约。
 */
@RestController
@RequestMapping("/reservations")
@RequiredArgsConstructor
@Tag(name = "14-预约模块")
public class ReservationController {

    private final ReservationService reservationService;
    private final OperationLogService operationLogService;

    @PostMapping
    @RequireRole(RoleConstant.READER)
    @Operation(summary = "预约图书（有可借副本时直接预留，否则排队）")
    public Result<Reservation> reserve(@Valid @RequestBody ReservationDTO dto) {
        Reservation reservation = reservationService.reserve(UserContext.getUserId(), dto.getBookId());
        // 2026-10-22：有可借副本时预约会直接进入"待取"，此时说"排队第 N 位"是错的
        String tip = ReservationStatusConstant.READY.equals(reservation.getStatus())
                ? "预约成功，已为您预留该书，请于 " + ReservationConstant.READY_HOLD_DAYS + " 天内到馆取书"
                : "预约成功，当前排队第 " + reservation.getQueuePosition() + " 位";
        return Result.ok(tip, reservation);
    }

    @DeleteMapping("/{id}")
    @RequireRole({RoleConstant.ADMIN, RoleConstant.LIBRARIAN, RoleConstant.READER})
    @Operation(summary = "取消预约（读者仅限本人）")
    public Result<Reservation> cancel(@PathVariable Long id) {
        return Result.ok("预约已取消", reservationService.cancel(id));
    }

    @GetMapping("/my")
    @RequireRole(RoleConstant.READER)
    @Operation(summary = "我的预约（后端强制过滤本人）")
    public Result<IPage<Reservation>> myPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Integer status) {
        page = Math.max(page, 1);
        size = Math.max(size, 1);
        return Result.ok(reservationService.myPage(page, size, status, UserContext.getUserId()));
    }

    @GetMapping
    @RequireRole({RoleConstant.ADMIN, RoleConstant.LIBRARIAN})
    @Operation(summary = "预约队列查询（馆员/管理员）")
    public Result<IPage<Reservation>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long bookId) {
        page = Math.max(page, 1);
        size = Math.max(size, 1);
        return Result.ok(reservationService.page(page, size, keyword, status, bookId));
    }

    @PostMapping("/expire")
    @RequireRole(RoleConstant.ADMIN)
    @Operation(summary = "手动回收已过期待取预约（幂等，供运维/演示）")
    public Result<Integer> expire() {
        int count = reservationService.expireOverdue();
        operationLogService.record("RESERVE_EXPIRE", "手动回收过期预约 " + count + " 条");
        return Result.ok("已回收过期预约 " + count + " 条", count);
    }
}

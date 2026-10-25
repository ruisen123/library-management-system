package com.library.controller;

import com.library.annotation.RequireRole;
import com.library.common.Result;
import com.library.common.constant.RoleConstant;
import com.library.service.NotificationService;
import com.library.service.OperationLogService;
import com.library.utils.UserContext;
import com.library.vo.NotificationPageVO;
import com.library.vo.NotificationScanVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内通知控制器
 *
 * 读取与已读均为"本人"语义：用户身份只取令牌（UserContext），不接受任何 userId 入参。
 */
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
@Tag(name = "15-消息模块")
public class NotificationController {

    private final NotificationService notificationService;
    private final OperationLogService operationLogService;

    @GetMapping
    @RequireRole(RoleConstant.READER)
    @Operation(summary = "我的通知分页（含未读数）")
    public Result<NotificationPageVO> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return Result.ok(notificationService.pageForUser(UserContext.getUserId(), page, size, unreadOnly));
    }

    @GetMapping("/unread-count")
    @RequireRole(RoleConstant.READER)
    @Operation(summary = "我的未读通知数（顶栏红点）")
    public Result<Long> unreadCount() {
        return Result.ok(notificationService.unreadCount(UserContext.getUserId()));
    }

    @PutMapping("/{id}/read")
    @RequireRole(RoleConstant.READER)
    @Operation(summary = "标记单条已读")
    public Result<Void> markRead(@PathVariable Long id) {
        notificationService.markRead(UserContext.getUserId(), id);
        return Result.ok();
    }

    @PutMapping("/read-all")
    @RequireRole(RoleConstant.READER)
    @Operation(summary = "全部标记已读")
    public Result<Integer> markAllRead() {
        return Result.ok("已全部标记为已读", notificationService.markAllRead(UserContext.getUserId()));
    }

    @PostMapping("/scan")
    @RequireRole(RoleConstant.ADMIN)
    @Operation(summary = "手动触发通知扫描（生成提醒 + 清理过期已读）")
    public Result<NotificationScanVO> scan() {
        NotificationScanVO vo = notificationService.scan();
        operationLogService.recordBestEffort(UserContext.getUserId(), "NOTIFICATION_SCAN",
                String.format("手动触发通知扫描：新增 %d 条，清理 %d 条",
                        vo.getCreated(), vo.getPurged()));
        return Result.ok(vo);
    }
}

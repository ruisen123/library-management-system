package com.library.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.library.annotation.RequireRole;
import com.library.common.Result;
import com.library.common.constant.RoleConstant;
import com.library.dto.BorrowDTO;
import com.library.entity.BorrowRecord;
import com.library.entity.SysUser;
import com.library.service.BorrowService;
import com.library.service.UserService;
import com.library.utils.UserContext;
import com.library.vo.ReturnResultVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 借阅核心控制器：借书 / 还书 / 续借 / 记录查询 / 读者检索
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "05-借阅模块")
public class BorrowController {

    private final BorrowService borrowService;
    private final UserService userService;

    @PostMapping("/borrow")
    @RequireRole({RoleConstant.ADMIN, RoleConstant.LIBRARIAN})
    @Operation(summary = "办理借书")
    public Result<BorrowRecord> borrow(@RequestBody BorrowDTO dto) {
        BorrowRecord record = borrowService.borrow(dto.getUserId(), dto.getBookId());
        return Result.ok("借书成功，应还日期 " + record.getDueDate(), record);
    }

    @PostMapping("/return")
    @RequireRole({RoleConstant.ADMIN, RoleConstant.LIBRARIAN})
    @Operation(summary = "办理还书（含逾期罚金）")
    public Result<ReturnResultVO> returnBook(@RequestBody BorrowDTO dto) {
        ReturnResultVO vo = borrowService.returnBook(dto.getRecordId());
        String msg = "还书成功";
        if (vo.getOverdueDays() != null && vo.getOverdueDays() > 0) {
            msg += "，逾期 " + vo.getOverdueDays() + " 天，应收罚金 " + vo.getFineAmount()
                    + " 元，请线下收取后在罚金管理中标记已缴";
        }
        return Result.ok(msg, vo);
    }

    @PostMapping("/renew")
    @RequireRole({RoleConstant.ADMIN, RoleConstant.READER, RoleConstant.LIBRARIAN})
    @Operation(summary = "续借（读者仅限本人记录）")
    public Result<BorrowRecord> renew(@RequestBody BorrowDTO dto) {
        BorrowRecord record = borrowService.renew(dto.getRecordId());
        return Result.ok("续借成功，新应还日期 " + record.getDueDate(), record);
    }

    @GetMapping("/borrow-records")
    @RequireRole({RoleConstant.ADMIN, RoleConstant.LIBRARIAN})
    @Operation(summary = "借阅记录分页查询（状态/日期范围筛选）")
    public Result<IPage<BorrowRecord>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") java.time.LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") java.time.LocalDate endDate) {
        // 分页参数归一化：MyBatis-Plus 在 size<1 时会跳过分页返回全表，此处强制下界为 1
        page = Math.max(page, 1);
        size = Math.max(size, 1);
        return Result.ok(borrowService.page(page, size, keyword, status, userId, startDate, endDate));
    }

    @GetMapping("/my-borrows")
    @RequireRole(RoleConstant.READER)
    @Operation(summary = "我的借阅（后端强制过滤本人）")
    public Result<IPage<BorrowRecord>> myPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Boolean history) {
        // 分页参数归一化：MyBatis-Plus 在 size<1 时会跳过分页返回全表，此处强制下界为 1
        page = Math.max(page, 1);
        size = Math.max(size, 1);
        return Result.ok(borrowService.myPage(page, size, status, UserContext.getUserId(),
                Boolean.TRUE.equals(history)));
    }

    @GetMapping("/readers")
    @RequireRole({RoleConstant.ADMIN, RoleConstant.LIBRARIAN})
    @Operation(summary = "读者检索（借书办理：证号/姓名模糊查询）")
    public Result<List<SysUser>> listReaders(@RequestParam(required = false) String keyword) {
        return Result.ok(userService.listReaders(keyword));
    }
}
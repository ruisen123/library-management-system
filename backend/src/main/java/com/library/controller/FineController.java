package com.library.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.library.annotation.RequireRole;
import com.library.common.Result;
import com.library.common.constant.RoleConstant;
import com.library.dto.CompensateDTO;
import com.library.entity.FineRecord;
import com.library.service.FineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 罚金控制器
 * 查询：系统管理员 + 图书管理员 + 读者（读者被后端强制过滤为本人）；
 * 收款/赔偿登记：系统管理员 + 图书管理员（读者不可自助标记已缴，须到馆线下缴纳）
 */
@RestController
@RequestMapping("/fines")
@RequiredArgsConstructor
@Tag(name = "06-罚金模块")
public class FineController {

    private final FineService fineService;

    @GetMapping
    @RequireRole({RoleConstant.ADMIN, RoleConstant.LIBRARIAN, RoleConstant.READER})
    @Operation(summary = "分页查询罚金")
    public Result<IPage<FineRecord>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long userId) {
        // 分页参数归一化：MyBatis-Plus 在 size<1 时会跳过分页返回全表，此处强制下界为 1
        page = Math.max(page, 1);
        size = Math.max(size, 1);
        return Result.ok(fineService.page(page, size, status, userId));
    }

    @PutMapping("/{id}/pay")
    @RequireRole({RoleConstant.ADMIN, RoleConstant.LIBRARIAN})
    @Operation(summary = "罚金收款（仅馆员/管理员；读者到馆线下缴纳，不可自助标记已缴）")
    public Result<Void> pay(@PathVariable Long id) {
        fineService.pay(id);
        return Result.ok("罚金已标记为已缴", null);
    }

    @PostMapping("/compensate")
    @RequireRole({RoleConstant.ADMIN, RoleConstant.LIBRARIAN})
    @Operation(summary = "破损/遗失赔偿登记")
    public Result<Void> compensate(@Valid @RequestBody CompensateDTO dto) {
        fineService.compensate(dto);
        return Result.ok("赔偿登记成功，库存不恢复", null);
    }
}
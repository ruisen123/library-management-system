package com.library.controller;

import com.library.annotation.RequireRole;
import com.library.common.Result;
import com.library.common.constant.RoleConstant;
import com.library.entity.Book;
import com.library.service.StatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 统计分析控制器（仅系统管理员）
 */
@RestController
@RequestMapping("/stats")
@RequiredArgsConstructor
@RequireRole(RoleConstant.ADMIN)
@Tag(name = "08-统计模块")
public class StatsController {

    private final StatsService statsService;

    @GetMapping("/overview")
    @Operation(summary = "数据总览")
    public Result<Map<String, Object>> overview() {
        return Result.ok(statsService.overview());
    }

    @GetMapping("/trend")
    @Operation(summary = "近12个月借阅趋势")
    public Result<Map<String, Object>> trend() {
        return Result.ok(statsService.trend());
    }

    @GetMapping("/pie")
    @Operation(summary = "分类借阅占比")
    public Result<List<Map<String, Object>>> pie() {
        return Result.ok(statsService.pie());
    }

    @GetMapping("/top")
    @Operation(summary = "借阅热度TOP10")
    public Result<List<Book>> top() {
        return Result.ok(statsService.top());
    }

    @GetMapping("/export")
    @Operation(summary = "导出 Excel 报表（汇总/借阅明细/罚金明细/热门图书）")
    public void export(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate endDate,
            HttpServletResponse response) throws IOException {
        byte[] data = statsService.exportReport(startDate, endDate);
        String fileName = "LibMS_报表_" + LocalDate.now() + ".xlsx";
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment;filename*=UTF-8''" + encoded);
        response.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
        response.setContentLength(data.length);
        response.getOutputStream().write(data);
    }
}
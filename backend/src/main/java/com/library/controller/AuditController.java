package com.library.controller;

import com.library.annotation.RequireRole;
import com.library.audit.AuditRule;
import com.library.audit.AuditRules;
import com.library.common.Result;
import com.library.common.constant.RoleConstant;
import com.library.service.AuditService;
import com.library.vo.AuditReportVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据一致性诊断控制器（仅系统管理员）
 *
 * 安全说明：规则由后端固定定义，接口【不接受】任何 SQL、表名或条件参数，
 * 避免把诊断接口做成可注入的通用查询入口；规则清单接口亦不回传 SQL 本体。
 */
@RestController
@RequestMapping("/audit")
@RequiredArgsConstructor
@RequireRole(RoleConstant.ADMIN)
@Tag(name = "12-数据一致性诊断模块")
public class AuditController {

    private final AuditService auditService;

    @PostMapping("/run")
    @Operation(summary = "执行数据一致性巡检（只读诊断）")
    public Result<AuditReportVO> run() {
        return Result.ok(auditService.run());
    }

    @GetMapping("/rules")
    @Operation(summary = "巡检规则清单（规则说明，不含 SQL）")
    public Result<List<Map<String, Object>>> rules() {
        List<Map<String, Object>> list = new ArrayList<>(AuditRules.CHECK_COUNT);
        for (AuditRule rule : AuditRules.ALL) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ruleId", rule.getId());
            item.put("name", rule.getName());
            item.put("level", rule.getLevel());
            item.put("severity", rule.getSeverity());
            item.put("scope", rule.getScope());
            item.put("expected", rule.getExpected());
            item.put("excludeNote", rule.getExcludeNote());
            list.add(item);
        }
        return Result.ok(list);
    }
}

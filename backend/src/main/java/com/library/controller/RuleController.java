package com.library.controller;

import com.library.annotation.RequireRole;
import com.library.common.Result;
import com.library.common.constant.FineConstant;
import com.library.common.constant.RoleConstant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 借阅规则控制器（只读展示，规则常量固化于 FineConstant，避免被误改破坏数据一致性）
 */
@RestController
@RequestMapping("/rule")
// 本控制器只有 GET /config（只读展示后端常量），对三角色开放读权限：
// 馆员需要向读者解释规则，读者也需要自己查规则；写死在常量里，无写端点，放开读无风险
@RequireRole({RoleConstant.ADMIN, RoleConstant.LIBRARIAN, RoleConstant.READER})
@Tag(name = "11-借阅规则模块")
public class RuleController {

    @GetMapping("/config")
    @Operation(summary = "借阅规则常量（只读）")
    public Result<Map<String, Object>> config() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("maxBorrow", FineConstant.MAX_BORROW);
        map.put("borrowDays", FineConstant.BORROW_DAYS);
        map.put("renewDays", FineConstant.RENEW_DAYS);
        map.put("finePerDay", FineConstant.FINE_PER_DAY);
        return Result.ok(map);
    }
}

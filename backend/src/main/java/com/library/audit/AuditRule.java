package com.library.audit;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 巡检规则元数据（声明式规约）
 * 一条规则 = 编号 + 名称 + 风险层级 + 严重级别 + 适用范围 + 期望值 + 排除条件 + 判定SQL + 定位SQL
 */
@Getter
@AllArgsConstructor
public class AuditRule {

    /** 规则编号（唯一，如 R1、R10a） */
    private final String id;

    /** 规则名称（人可读） */
    private final String name;

    /** 风险层级：L1 真实业务风险 / L2 并发兜底 / L3 人工维护护栏 */
    private final String level;

    /** 严重级别：高 / 中 / 低 */
    private final String severity;

    /** 适用范围（哪些数据接受检查） */
    private final String scope;

    /** 期望值说明（通过时应满足的关系） */
    private final String expected;

    /** 排除条件（哪些合法情况不判为异常；无则空串） */
    private final String excludeNote;

    /** 判定 SQL（唯一事实源，取自《规则清单唯一口径表》） */
    private final String countSql;

    /** 定位 SQL（取异常记录的可读标识，最多 5 条） */
    private final String locationSql;
}

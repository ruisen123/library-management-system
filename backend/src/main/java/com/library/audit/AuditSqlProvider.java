package com.library.audit;

import java.util.Map;

/**
 * 巡检 SQL 提供者
 *
 * 安全要点：SQL 一律来自规约表（唯一事实源），接口不接收调用方传入的任何 SQL 片段、
 * 表名或条件，避免把诊断接口做成可注入的通用查询入口。
 */
public class AuditSqlProvider {

    /** 判定 SQL：统计命中异常行数 */
    public static String countSql(Map<String, Object> params) {
        return resolve(params).getCountSql();
    }

    /** 定位 SQL：取异常记录的可读标识（SQL 内已 LIMIT 5） */
    public static String locationSql(Map<String, Object> params) {
        return resolve(params).getLocationSql();
    }

    private static AuditRule resolve(Map<String, Object> params) {
        Object id = params == null ? null : params.get("ruleId");
        AuditRule rule = AuditRules.byId(id == null ? null : id.toString());
        if (rule == null) {
            throw new IllegalArgumentException("未知的巡检规则编号：" + id);
        }
        return rule;
    }
}

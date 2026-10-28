package com.library.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 巡检规则规约表契约测试。
 *
 * 这些断言是「运行时一致性巡检」的代码侧契约：
 * 规则条数、编号、层级、严重级别与 SQL 形态一旦被改动而文档未同步，这里先红。
 */
@DisplayName("AuditRules：巡检规则规约表契约")
class AuditRulesTest {

    private static final List<AuditRule> RULES = AuditRules.ALL;

    @Test
    @DisplayName("规则共 14 项检查（R1-R8 + R9a/R9b + R10a-R10d）")
    void hasFourteenChecks() {
        assertEquals(14, RULES.size());
    }

    @Test
    @DisplayName("规则编号唯一")
    void idsAreUnique() {
        Set<String> seen = new HashSet<>();
        for (AuditRule r : RULES) {
            assertTrue(seen.add(r.getId()), "规则编号重复：" + r.getId());
        }
    }

    @Test
    @DisplayName("编号格式为 R+数字，可带 a-d 后缀")
    void idFormat() {
        for (AuditRule r : RULES) {
            assertTrue(r.getId().matches("R\\d+[a-d]?"), "编号格式异常：" + r.getId());
        }
    }

    @Test
    @DisplayName("风险层级只允许 L1 / L2 / L3")
    void levelIsValid() {
        Set<String> allowed = Set.of("L1", "L2", "L3");
        for (AuditRule r : RULES) {
            assertTrue(allowed.contains(r.getLevel()), "层级非法：" + r.getId() + " = " + r.getLevel());
        }
    }

    @Test
    @DisplayName("严重级别只允许 高 / 中 / 低")
    void severityIsValid() {
        Set<String> allowed = Set.of("高", "中", "低");
        for (AuditRule r : RULES) {
            assertTrue(allowed.contains(r.getSeverity()), "严重级别非法：" + r.getId() + " = " + r.getSeverity());
        }
    }

    @Test
    @DisplayName("每条规则都有判定 SQL，且不使用 SELECT *")
    void countSqlIsPresentAndNarrow() {
        for (AuditRule r : RULES) {
            String sql = r.getCountSql();
            assertNotNull(sql, r.getId() + " 缺少判定 SQL");
            String upper = sql.trim().toUpperCase();
            assertTrue(upper.startsWith("SELECT"), r.getId() + " 判定 SQL 不是查询语句");
            assertFalse(upper.contains("SELECT *"), r.getId() + " 判定 SQL 使用了 SELECT *");
        }
    }

    @Test
    @DisplayName("每条规则都有定位 SQL（异常可定位到具体记录）")
    void locationSqlIsPresent() {
        for (AuditRule r : RULES) {
            String sql = r.getLocationSql();
            assertNotNull(sql, r.getId() + " 缺少定位 SQL");
            assertFalse(sql.isBlank(), r.getId() + " 定位 SQL 为空");
        }
    }

    @Test
    @DisplayName("关键规则存在：库存自洽 R1 与已删数据族 R10a-R10d")
    void keyRulesPresent() {
        Set<String> ids = new HashSet<>();
        RULES.forEach(r -> ids.add(r.getId()));
        assertTrue(ids.containsAll(Set.of("R1", "R10a", "R10b", "R10c", "R10d")), "缺少关键规则：" + ids);
    }
}

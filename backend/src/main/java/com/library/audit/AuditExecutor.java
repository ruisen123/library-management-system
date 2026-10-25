package com.library.audit;

import com.library.mapper.AuditMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 只读巡检执行器（独立 Bean，专责事务边界）
 *
 * 设计要点一（单事务 = 单快照）：全部规则必须在【同一个只读事务】内执行，
 * 报告才是自洽的——若每条 SQL 各自提交，各条读到不同时点，报告会描述一个
 * "在任一真实时刻都未同时成立"的状态。
 * 该性质的成立依赖 MySQL 默认的 REPEATABLE READ 隔离级别：实测 RR 下同一事务
 * 两次读为同一快照，而 READ COMMITTED 下两次读会不同。
 *
 * 设计要点二（只读边界为何独立成 Bean）：上层服务还需写巡检审计日志，
 * 若把 readOnly 事务加在服务上，日志写入会被数据库以只读事务拒绝；
 * 拆开后，只读边界由代码强制，而非口头承诺。
 */
@Component
@RequiredArgsConstructor
public class AuditExecutor {

    private final AuditMapper auditMapper;

    /** 单项检查的执行结果 */
    @Getter
    @AllArgsConstructor
    public static class RuleResult {
        private final AuditRule rule;
        private final long count;
        private final List<String> samples;
    }

    /** 在同一只读事务内依次执行全部检查项 */
    @Transactional(readOnly = true)
    public List<RuleResult> executeAll() {
        List<RuleResult> results = new ArrayList<>(AuditRules.CHECK_COUNT);
        for (AuditRule rule : AuditRules.ALL) {
            Long hit = auditMapper.countByRule(rule.getId());
            long count = hit == null ? 0L : hit;
            // 仅在确有异常时才查定位样本：健康库下不产生额外查询开销
            List<String> samples = count > 0 ? auditMapper.locateByRule(rule.getId()) : List.of();
            results.add(new RuleResult(rule, count, samples));
        }
        return results;
    }
}

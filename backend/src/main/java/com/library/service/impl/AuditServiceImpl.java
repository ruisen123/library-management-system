package com.library.service.impl;

import com.library.audit.AuditExecutor;
import com.library.audit.AuditRule;
import com.library.audit.AuditRules;
import com.library.service.AuditService;
import com.library.service.OperationLogService;
import com.library.utils.UserContext;
import com.library.vo.AuditReportVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 数据一致性巡检服务实现
 *
 * 只读边界说明：本类【不加】@Transactional(readOnly = true)。
 * 规则执行在 AuditExecutor 的独立只读事务内完成（单事务 = 单快照）；
 * 执行完毕后，本类在事务【外】写一条巡检审计日志（operation_log）。
 * 若把日志写入放在只读事务内，数据库会以只读事务为由拒绝写入。
 *
 * 报告不落库：仅由接口即时返回，另用一条操作日志记录"何时由何人发起过巡检"，
 * 从而不新增数据表（保持既有 10 张表口径）。
 */
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    private static final DateTimeFormatter SNAPSHOT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AuditExecutor auditExecutor;
    private final OperationLogService operationLogService;

    @Override
    public AuditReportVO run() {
        String snapshotAt = LocalDateTime.now().format(SNAPSHOT_FMT);

        // 1. 同一只读事务内执行全部检查项
        List<AuditExecutor.RuleResult> results = auditExecutor.executeAll();

        // 2. 组装报告
        AuditReportVO report = new AuditReportVO();
        report.setSnapshotAt(snapshotAt);
        report.setRuleCount(AuditRules.RULE_COUNT);
        report.setCheckCount(AuditRules.CHECK_COUNT);

        int passed = 0;
        int anomaly = 0;
        int high = 0;
        int medium = 0;
        int low = 0;

        for (AuditExecutor.RuleResult result : results) {
            AuditRule rule = result.getRule();
            AuditReportVO.Item item = new AuditReportVO.Item();
            item.setRuleId(rule.getId());
            item.setName(rule.getName());
            item.setLevel(rule.getLevel());
            item.setSeverity(rule.getSeverity());
            item.setCount(result.getCount());
            item.setPassed(result.getCount() == 0L);
            item.setScope(rule.getScope());
            item.setExpected(rule.getExpected());
            item.setExcludeNote(rule.getExcludeNote());
            item.setSamples(result.getSamples());

            if (item.isPassed()) {
                passed++;
            } else {
                anomaly++;
                if ("高".equals(rule.getSeverity())) {
                    high++;
                } else if ("中".equals(rule.getSeverity())) {
                    medium++;
                } else {
                    low++;
                }
            }
            report.getItems().add(item);
        }

        report.setPassedCount(passed);
        report.setAnomalyItemCount(anomaly);
        report.setHighCount(high);
        report.setMediumCount(medium);
        report.setLowCount(low);
        report.setConclusion(anomaly == 0
                ? "本次巡检未发现规则范围内的数据不一致"
                : "本次巡检发现 " + anomaly + " 项异常，请按定位信息人工核查后重新巡检");

        // 3. 事务外写审计日志（仅记录动作，不保存报告本体，避免新增数据表）
        operationLogService.recordBestEffort(UserContext.getUserId(), "DATA_AUDIT", String.format(
                "数据一致性巡检：检查 %d 项，异常 %d 项（高 %d / 中 %d / 低 %d）",
                report.getCheckCount(), anomaly, high, medium, low));

        return report;
    }
}

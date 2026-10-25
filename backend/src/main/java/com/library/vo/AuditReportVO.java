package com.library.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据一致性巡检报告
 */
@Data
public class AuditReportVO {

    /** 快照时点：本次巡检读取的一致数据视图对应的时刻 */
    private String snapshotAt;

    /** 规则条数（10） */
    private int ruleCount;

    /** 检查项条数（14） */
    private int checkCount;

    /** 通过项数 */
    private int passedCount;

    /** 异常项数 */
    private int anomalyItemCount;

    /** 高级别异常项数 */
    private int highCount;

    /** 中级别异常项数 */
    private int mediumCount;

    /** 低级别异常项数 */
    private int lowCount;

    /** 结论文案 */
    private String conclusion;

    /** 明细 */
    private List<Item> items = new ArrayList<>();

    /** 单项检查结果 */
    @Data
    public static class Item {
        private String ruleId;
        private String name;
        private String level;
        private String severity;
        private boolean passed;
        private long count;
        private String scope;
        private String expected;
        private String excludeNote;
        private List<String> samples = new ArrayList<>();
    }
}

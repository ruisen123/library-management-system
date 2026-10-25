package com.library.audit;

import java.util.List;

/**
 * 巡检规则规约表（唯一事实源）
 *
 * 口径（《规则清单唯一口径表》v1.0）：
 *   - 10 条规则（R1–R10），展开 14 项检查（R9、R10 为"族"规则）
 *   - 判定 SQL 为唯一事实源；定位 SQL 仅用于异常可读标识
 *   - 修改任何 SQL 必须同步重跑「等价性验证」与「万级性能实测」
 *
 * 删除可见性：每条规则自行声明是否包含已逻辑删除数据（自定义 SQL 不经 @TableLogic 改写）；
 * 对侧引用条件显式限定，避免把"合法历史"误判为异常。
 */
public final class AuditRules {

    private AuditRules() {
    }

    public static final List<AuditRule> ALL = List.of(
            new AuditRule("R1", "图书库存自洽", "L1", "高",
                    "全部图书（含已逻辑删除）",
                    "馆藏总册数 = 可借册数 + 在借册数 + 已赔偿册数",
                    "",
                    "SELECT COUNT(*) FROM book b LEFT JOIN (SELECT book_id,COUNT(*) c FROM borrow_record WHERE status IN(1,3) GROUP BY book_id) t ON t.book_id=b.id WHERE (b.total_stock-b.stock)<>COALESCE(t.c,0);",
                    "SELECT CONCAT('bookId=', b.id) FROM book b LEFT JOIN (SELECT book_id,COUNT(*) c FROM borrow_record WHERE status IN(1,3) GROUP BY book_id) t ON t.book_id=b.id WHERE (b.total_stock-b.stock)<>COALESCE(t.c,0) LIMIT 5"),

            new AuditRule("R2", "读者在借数一致", "L1", "高",
                    "读者角色账号（role='READER'，含已逻辑删除）",
                    "在借册数 = 该读者状态为在借的流水条数",
                    "",
                    "SELECT COUNT(*) FROM sys_user u WHERE u.role='READER' AND u.borrowed_count<>(SELECT COUNT(*) FROM borrow_record br WHERE br.user_id=u.id AND br.status=1);",
                    "SELECT CONCAT('userId=', u.id) FROM sys_user u WHERE u.role='READER' AND u.borrowed_count<>(SELECT COUNT(*) FROM borrow_record br WHERE br.user_id=u.id AND br.status=1) LIMIT 5"),

            new AuditRule("R3", "在借记录无归还日期", "L3", "低",
                    "状态为在借（status=1）的借阅流水",
                    "在借记录不应有归还日期",
                    "不含已赔偿（status=3）：赔偿办理时不写归还日期，演示数据中该状态亦存在归还日期，两头不一致故不纳入",
                    "SELECT COUNT(*) FROM borrow_record WHERE status=1 AND return_date IS NOT NULL;",
                    "SELECT CONCAT('recordId=', id) FROM borrow_record WHERE status=1 AND return_date IS NOT NULL LIMIT 5"),

            new AuditRule("R4", "已还记录有归还日期", "L3", "低",
                    "状态为已还（status=2）的借阅流水",
                    "已还记录应有归还日期，且不早于借出日期",
                    "不含已赔偿（status=3）：同 R3 的排除理由",
                    "SELECT COUNT(*) FROM borrow_record WHERE status=2 AND (return_date IS NULL OR return_date<borrow_date);",
                    "SELECT CONCAT('recordId=', id) FROM borrow_record WHERE status=2 AND (return_date IS NULL OR return_date<borrow_date) LIMIT 5"),

            new AuditRule("R5", "日期顺序正确", "L3", "低",
                    "全部借阅流水",
                    "应还日期不早于借出日期",
                    "",
                    "SELECT COUNT(*) FROM borrow_record WHERE due_date<borrow_date;",
                    "SELECT CONCAT('recordId=', id) FROM borrow_record WHERE due_date<borrow_date LIMIT 5"),

            new AuditRule("R6", "罚金归属一致", "L3", "低",
                    "全部罚金记录及其关联借阅",
                    "罚金归属读者与关联借阅的读者一致",
                    "",
                    "SELECT COUNT(*) FROM fine_record f JOIN borrow_record br ON br.id=f.borrow_id WHERE f.user_id<>br.user_id;",
                    "SELECT CONCAT('fineId=', f.id, ', fineUserId=', f.user_id, ', borrowUserId=', br.user_id) FROM fine_record f JOIN borrow_record br ON br.id=f.borrow_id WHERE f.user_id<>br.user_id LIMIT 5"),

            new AuditRule("R7", "缴纳状态与时间配套", "L3", "低",
                    "全部罚金记录",
                    "已缴（status=2）必有缴纳时间；未缴（status=1）必无缴纳时间",
                    "",
                    "SELECT COUNT(*) FROM fine_record WHERE (status=2 AND paid_time IS NULL) OR (status=1 AND paid_time IS NOT NULL);",
                    "SELECT CONCAT('fineId=', id, ', status=', status) FROM fine_record WHERE (status=2 AND paid_time IS NULL) OR (status=1 AND paid_time IS NOT NULL) LIMIT 5"),

            new AuditRule("R8", "逾期罚金金额可重算", "L3", "低",
                    "类型为逾期（type=1）且关联借阅已归还的罚金",
                    "金额 = 逾期天数 × 0.5 元（逾期天数=归还日期-应还日期）",
                    "仅限 type=1；未归还记录不参与；赔偿类罚金（type=2/3）为手工定价，不参与重算",
                    "SELECT COUNT(*) FROM fine_record f JOIN borrow_record br ON br.id=f.borrow_id WHERE f.type=1 AND br.return_date IS NOT NULL AND f.amount<>ROUND(DATEDIFF(br.return_date,br.due_date)*0.5,2);",
                    "SELECT CONCAT('fineId=', f.id) FROM fine_record f JOIN borrow_record br ON br.id=f.borrow_id WHERE f.type=1 AND br.return_date IS NOT NULL AND f.amount<>ROUND(DATEDIFF(br.return_date,br.due_date)*0.5,2) LIMIT 5"),

            new AuditRule("R9a", "已赔偿记录必有赔偿罚金", "L3", "低",
                    "状态为已赔偿（status=3）的借阅流水",
                    "已赔偿记录应存在关联的赔偿类罚金（type∈{2,3}）",
                    "",
                    "SELECT COUNT(*) FROM borrow_record br WHERE br.status=3 AND NOT EXISTS(SELECT 1 FROM fine_record f WHERE f.borrow_id=br.id AND f.type IN(2,3));",
                    "SELECT CONCAT('recordId=', br.id) FROM borrow_record br WHERE br.status=3 AND NOT EXISTS(SELECT 1 FROM fine_record f WHERE f.borrow_id=br.id AND f.type IN(2,3)) LIMIT 5"),

            new AuditRule("R9b", "赔偿罚金必有已赔偿记录", "L3", "低",
                    "类型为赔偿（type∈{2,3}）的罚金及其关联借阅",
                    "赔偿类罚金对应的借阅记录应为已赔偿状态",
                    "",
                    "SELECT COUNT(*) FROM fine_record f JOIN borrow_record br ON br.id=f.borrow_id WHERE f.type IN(2,3) AND br.status<>3;",
                    "SELECT CONCAT('fineId=', f.id, ', recordId=', br.id) FROM fine_record f JOIN borrow_record br ON br.id=f.borrow_id WHERE f.type IN(2,3) AND br.status<>3 LIMIT 5"),

            new AuditRule("R10a", "已删图书无在借流水", "L2", "中",
                    "已逻辑删除（deleted=1）的图书及其借阅流水",
                    "已删除图书不应存在在借（status=1）流水",
                    "已删图书的已还流水不判异常（属合法历史留痕）",
                    "SELECT COUNT(*) FROM book b WHERE b.deleted=1 AND EXISTS(SELECT 1 FROM borrow_record br WHERE br.book_id=b.id AND br.status=1);",
                    "SELECT CONCAT('bookId=', b.id) FROM book b WHERE b.deleted=1 AND EXISTS(SELECT 1 FROM borrow_record br WHERE br.book_id=b.id AND br.status=1) LIMIT 5"),

            new AuditRule("R10b", "已删用户无在借或欠费", "L2", "中",
                    "已逻辑删除（deleted=1）的用户",
                    "已删除用户不应存在在借流水或未缴罚金",
                    "已删用户的已还流水不判异常（属合法历史留痕）",
                    "SELECT COUNT(*) FROM sys_user u WHERE u.deleted=1 AND (EXISTS(SELECT 1 FROM borrow_record br WHERE br.user_id=u.id AND br.status=1) OR EXISTS(SELECT 1 FROM fine_record f WHERE f.user_id=u.id AND f.status=1));",
                    "SELECT CONCAT('userId=', u.id) FROM sys_user u WHERE u.deleted=1 AND (EXISTS(SELECT 1 FROM borrow_record br WHERE br.user_id=u.id AND br.status=1) OR EXISTS(SELECT 1 FROM fine_record f WHERE f.user_id=u.id AND f.status=1)) LIMIT 5"),

            new AuditRule("R10c", "已删分类不被未删图书引用", "L2", "中",
                    "已逻辑删除（deleted=1）的分类",
                    "已删除分类不应被未删除图书引用",
                    "已删图书引用已删分类不判异常（属合法历史）",
                    "SELECT COUNT(*) FROM category c WHERE c.deleted=1 AND EXISTS(SELECT 1 FROM book b WHERE b.category_id=c.id AND b.deleted=0);",
                    "SELECT CONCAT('categoryId=', c.id) FROM category c WHERE c.deleted=1 AND EXISTS(SELECT 1 FROM book b WHERE b.category_id=c.id AND b.deleted=0) LIMIT 5"),

            new AuditRule("R10d", "已删出版社不被未删图书引用", "L2", "中",
                    "已逻辑删除（deleted=1）的出版社",
                    "已删除出版社不应被未删除图书引用",
                    "已删图书引用已删出版社不判异常（属合法历史）",
                    "SELECT COUNT(*) FROM publisher p WHERE p.deleted=1 AND EXISTS(SELECT 1 FROM book b WHERE b.publisher_id=p.id AND b.deleted=0);",
                    "SELECT CONCAT('publisherId=', p.id) FROM publisher p WHERE p.deleted=1 AND EXISTS(SELECT 1 FROM book b WHERE b.publisher_id=p.id AND b.deleted=0) LIMIT 5")
    );

    /** 检查项条数（可执行粒度，14） */
    public static final int CHECK_COUNT = ALL.size();

    /** 规则条数（R1–R10 共 10 条；R9、R10 为族规则，由多个检查项展开） */
    public static final int RULE_COUNT = (int) ALL.stream()
            .map(r -> r.getId().replaceAll("[a-z]$", ""))
            .distinct()
            .count();

    /** 按编号取规则；不存在返回 null */
    public static AuditRule byId(String id) {
        if (id == null) {
            return null;
        }
        for (AuditRule rule : ALL) {
            if (rule.getId().equals(id)) {
                return rule;
            }
        }
        return null;
    }
}

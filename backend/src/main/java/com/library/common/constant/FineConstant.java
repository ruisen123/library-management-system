package com.library.common.constant;

/**
 * 罚金类型与缴纳状态常量（对应 fine_record）
 */
public interface FineConstant {

    // 罚金类型
    Integer TYPE_OVERDUE = 1;  // 逾期罚金
    Integer TYPE_DAMAGE = 2;   // 破损赔偿
    Integer TYPE_LOST = 3;     // 遗失赔偿

    // 缴纳状态
    Integer STATUS_UNPAID = 1; // 未缴
    Integer STATUS_PAID = 2;   // 已缴

    // 业务规则（与设计文档 docs/05 一致）
    int MAX_BORROW = 5;        // 读者可借上限
    int BORROW_DAYS = 30;      // 借期（天）
    int RENEW_DAYS = 30;       // 续借延长天数
    double FINE_PER_DAY = 0.5; // 逾期罚金单价（元/天）
}

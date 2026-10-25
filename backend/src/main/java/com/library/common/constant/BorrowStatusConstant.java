package com.library.common.constant;

/**
 * 借阅状态常量（对应 borrow_record.status）
 */
public interface BorrowStatusConstant {
    Integer BORROWING = 1; // 在借
    Integer RETURNED = 2;  // 已还
    Integer COMPENSATED = 3; // 已赔偿
}

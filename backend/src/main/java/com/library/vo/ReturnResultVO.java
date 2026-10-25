package com.library.vo;

import com.library.entity.BorrowRecord;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 还书结果：还书成功后按需携带逾期天数与应收罚金，供前端提示
 */
@Data
public class ReturnResultVO {

    /** 归还后的借阅记录 */
    private BorrowRecord record;

    /** 逾期天数（0 表示未逾期） */
    private Integer overdueDays;

    /** 应收罚金（未逾期为 null） */
    private BigDecimal fineAmount;
}
package com.library.common.constant;

/**
 * 预约业务参数（与借阅规则一样集中写死，便于论文口径与测试断言保持一致）
 */
public interface ReservationConstant {
    /** 到书后保留天数：逾期未取则顺延给下一位 */
    int READY_HOLD_DAYS = 7;
    /** 单个读者同时有效的预约上限（排队中 + 待取书） */
    int MAX_ACTIVE_PER_USER = 5;
}

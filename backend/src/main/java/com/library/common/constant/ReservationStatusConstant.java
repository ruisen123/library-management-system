package com.library.common.constant;

/**
 * 预约状态常量（对应 reservation.status）
 */
public interface ReservationStatusConstant {
    Integer WAITING = 1;    // 排队中
    Integer READY = 2;      // 到书待取
    Integer FULFILLED = 3;  // 已完成（读者已借走）
    Integer CANCELLED = 4;  // 已取消
    Integer EXPIRED = 5;    // 已过期（到书后 7 天未取）
}

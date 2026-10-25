package com.library.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.library.entity.Reservation;

/**
 * 预约排队服务
 *
 * 语义约定（与 docs/03、docs/05 同步）：
 * 1. 仅当图书可借数为 0 时允许预约；有库存时提示直接借阅。
 * 2. 预约按 queue_no 先来先服务；还书/取消/过期后自动把队首提升为"待取书"（7 天）。
 * 3. 待取书副本不扣库存（库存仍是"可借册数"），但借书时按"待取预约数"保护队首，
 *    避免其他读者把刚归还的副本直接借走；巡检 R1 的"总册数=可借+在借+已赔偿"口径不变。
 */
public interface ReservationService {

    Reservation reserve(Long userId, Long bookId);

    Reservation cancel(Long reservationId);

    IPage<Reservation> myPage(int page, int size, Integer status, Long userId);

    IPage<Reservation> page(int page, int size, String keyword, Integer status, Long bookId);

    /** 借书前置校验：待取预约优先，排队未到号者不得插队 */
    void assertBorrowAllowed(Long userId, Long bookId, int stock);

    /** 借书成功后把该读者的有效预约标记为已完成 */
    void fulfillOnBorrow(Long userId, Long bookId);

    /** 还书后触发队首到书（可提升多本，取决于可借库存） */
    void promoteAfterReturn(Long bookId);

    /** 图书是否存在有效预约（排队中/待取书）——删除图书前置守卫 */
    boolean hasActiveReservation(Long bookId);

    /** 续借前置校验：被预约的图书不允许续借（否则队首会被无限期拖延） */
    void assertRenewAllowed(Long bookId);

    /** 用户删除/冻结/改角色时取消其全部有效预约，并顺延队首 */
    int cancelActiveByUser(Long userId);

    /** 回收已过期待取预约并顺延，返回回收条数（定时任务与运维手动触发共用） */
    int expireOverdue();
}

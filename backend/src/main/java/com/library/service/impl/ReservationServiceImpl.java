package com.library.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.library.common.ResultCode;
import com.library.common.constant.BorrowStatusConstant;
import com.library.common.constant.NotificationConstant;
import com.library.common.constant.ReservationConstant;
import com.library.common.constant.ReservationStatusConstant;
import com.library.common.constant.RoleConstant;
import com.library.common.exception.BusinessException;
import com.library.entity.Book;
import com.library.entity.BorrowRecord;
import com.library.entity.Reservation;
import com.library.entity.SysUser;
import com.library.event.NotificationEvent;
import com.library.mapper.BookMapper;
import com.library.mapper.BorrowRecordMapper;
import com.library.mapper.ReservationMapper;
import com.library.mapper.SysUserMapper;
import com.library.service.OperationLogService;
import com.library.service.ReaderPolicyService;
import com.library.service.ReservationService;
import com.library.utils.SqlLikeUtils;
import com.library.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 预约排队服务实现
 *
 * 并发口径：
 * - 预约序号在锁定图书行后分配（FOR UPDATE），同书并发预约不会拿到相同 queue_no；
 * - 状态流转全部使用条件更新（WHERE status=...），重复提交/定时任务与手动触发并发时最多成功一次；
 * - 待取书不扣库存，借书时以"待取预约数"保护队首，保持巡检 R1 的库存口径不变。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationServiceImpl implements ReservationService {

    private final ReservationMapper reservationMapper;
    private final BookMapper bookMapper;
    private final SysUserMapper sysUserMapper;
    private final BorrowRecordMapper borrowRecordMapper;
    private final OperationLogService operationLogService;
    private final ReaderPolicyService readerPolicyService;
    private final ApplicationEventPublisher eventPublisher;
    private final PlatformTransactionManager transactionManager;

    /** 预约过期回收互斥（与通知扫描同款，见 expireOverdue）：定时任务与手动触发并发时只放行一个 */
    private final AtomicBoolean expiring = new AtomicBoolean(false);

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Reservation reserve(Long userId, Long bookId) {
        if (userId == null || bookId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "读者与图书不能为空");
        }
        // 先锁图书行，再建立本事务的读视图：之后的读者/预约普通读都能看到并发 reserve
        // 已提交的结果；同书并发已被图书行锁串行化，无需再锁预约表。
        bookMapper.lockByIdForUpdate(bookId);
        Book book = bookMapper.selectByIdForUpdate(bookId);
        if (book == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "图书不存在");
        }
        // 锁用户行：避免校验后被并发改角色/冻结/删除，仍插入有效预约。
        // 锁序 book -> user，与借书一致；用户更新事务不会再锁图书行（顺延延后到 afterCommit）。
        SysUser reader = sysUserMapper.selectByIdForUpdate(userId);
        if (reader == null || !RoleConstant.READER.equals(reader.getRole())) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "读者不存在");
        }
        if (reader.getStatus() != null && reader.getStatus() == 0) {
            throw new BusinessException("读者账号已冻结，无法预约");
        }
        readerPolicyService.assertCardValid(reader, "预约");
        readerPolicyService.assertBorrowLimitAvailable(reader, "预约");
        readerPolicyService.assertNoOverdue(reader, "预约");
        readerPolicyService.assertUnpaidWithinLimit(reader, "预约");
        int stock = book.getStock() == null ? 0 : book.getStock();
        // 2026-10-22（作者要求）：不再拒绝"当前有可借副本"的预约。
        // 若此刻确有未被占用的副本（stock - 已有待取 > 0），预约直接进入"待取书"，
        // 相当于替读者把这本预留下来；否则进入排队，等有人还书后顺延。
        // 好处：①"有库存也能约"②不会出现"库存 1 却被占住时约不上也借不到"的死锁。
        Long readyCnt = reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getBookId, bookId)
                .eq(Reservation::getStatus, ReservationStatusConstant.READY));
        int ready = readyCnt == null ? 0 : readyCnt.intValue();
        // 只要还有排队中的读者，新预约必须排在队尾：不能让"库存暂时大于待取数"的窗口被插队。
        Reservation waitingHead = reservationMapper.selectOne(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getBookId, bookId)
                .eq(Reservation::getStatus, ReservationStatusConstant.WAITING)
                .orderByAsc(Reservation::getQueueNo)
                .last("LIMIT 1"));
        boolean reserveNow = waitingHead == null && (stock - ready) > 0;
        Long sameBook = reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getBookId, bookId)
                .eq(Reservation::getUserId, userId)
                .in(Reservation::getStatus, ReservationStatusConstant.WAITING, ReservationStatusConstant.READY));
        if (sameBook != null && sameBook > 0) {
            throw new BusinessException("您已预约该书，请勿重复提交");
        }
        Long activeCount = reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getUserId, userId)
                .in(Reservation::getStatus, ReservationStatusConstant.WAITING, ReservationStatusConstant.READY));
        if (activeCount != null && activeCount >= ReservationConstant.MAX_ACTIVE_PER_USER) {
            throw new BusinessException("最多同时保留 " + ReservationConstant.MAX_ACTIVE_PER_USER + " 个有效预约，请先取消或等待");
        }
        Long borrowing = borrowRecordMapper.selectCount(new LambdaQueryWrapper<BorrowRecord>()
                .eq(BorrowRecord::getUserId, userId)
                .eq(BorrowRecord::getBookId, bookId)
                .eq(BorrowRecord::getStatus, BorrowStatusConstant.BORROWING));
        if (borrowing != null && borrowing > 0) {
            throw new BusinessException("您正在借阅该书，无需预约");
        }

        Integer maxQueueNo = reservationMapper.selectMaxQueueNo(bookId);
        Reservation reservation = new Reservation();
        reservation.setBookId(bookId);
        reservation.setUserId(userId);
        reservation.setQueueNo((maxQueueNo == null ? 0 : maxQueueNo) + 1);
        if (reserveNow) {
            // 有可用副本：直接预留（等同"已到书"），7 天内到馆取书
            LocalDateTime now = LocalDateTime.now();
            reservation.setStatus(ReservationStatusConstant.READY);
            reservation.setReadyTime(now);
            reservation.setExpireTime(now.plusDays(ReservationConstant.READY_HOLD_DAYS));
        } else {
            reservation.setStatus(ReservationStatusConstant.WAITING);
        }
        try {
            reservationMapper.insert(reservation);
        } catch (DuplicateKeyException e) {
            // 唯一索引 (book_id, queue_no) 兜底并发竞态；锁行后正常不会触发
            throw new BusinessException("预约提交冲突，请刷新后重试");
        }
        fillNames(List.of(reservation));
        operationLogService.record("RESERVE", String.format("读者[%s %s]预约《%s》，排队序号 %d",
                StrUtil.blankToDefault(reader.getReaderNo(), "未设置"), reader.getRealName(),
                book.getTitle(), reservation.getQueueNo()));
        return reservation;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Reservation cancel(Long reservationId) {
        Reservation reservation = reservationMapper.selectById(reservationId);
        if (reservation == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "预约记录不存在");
        }
        if (RoleConstant.READER.equals(UserContext.getRole())
                && !Objects.equals(reservation.getUserId(), UserContext.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "只能取消本人的预约");
        }
        if (!ReservationStatusConstant.WAITING.equals(reservation.getStatus())
                && !ReservationStatusConstant.READY.equals(reservation.getStatus())) {
            throw new BusinessException("该预约已结束，无法取消");
        }
        // 先锁图书行，再改预约状态：与借书/还书的 book -> reservation 锁序一致。
        // 否则取消/提升会先持有预约行、再去读库存，和并发借书形成 AB-BA 竞争，
        // 可能把库存已被借走的预约提升为“待取”。
        bookMapper.lockByIdForUpdate(reservation.getBookId());
        int readyRows = reservationMapper.update(null, new LambdaUpdateWrapper<Reservation>()
                .eq(Reservation::getId, reservationId)
                .eq(Reservation::getStatus, ReservationStatusConstant.READY)
                .set(Reservation::getStatus, ReservationStatusConstant.CANCELLED));
        boolean wasReady = readyRows > 0;
        if (!wasReady) {
            int waitingRows = reservationMapper.update(null, new LambdaUpdateWrapper<Reservation>()
                    .eq(Reservation::getId, reservationId)
                    .eq(Reservation::getStatus, ReservationStatusConstant.WAITING)
                    .set(Reservation::getStatus, ReservationStatusConstant.CANCELLED));
            if (waitingRows == 0) {
                throw new BusinessException("预约状态已变化，请刷新后重试");
            }
        }
        reservation.setStatus(ReservationStatusConstant.CANCELLED);
        if (wasReady) {
            // 待取名额释放后立即顺延给下一位；此时已持有图书行锁，库存读数不会被并发借书改写
            promoteNext(reservation.getBookId());
        }
        fillNames(List.of(reservation));
        operationLogService.record("RESERVE_CANCEL", String.format("取消预约：%s 的《%s》预约（序号 %d）",
                StrUtil.blankToDefault(reservation.getReaderNo(), "读者"), reservation.getBookTitle(),
                reservation.getQueueNo()));
        return reservation;
    }

    @Override
    public IPage<Reservation> myPage(int page, int size, Integer status, Long userId) {
        LambdaQueryWrapper<Reservation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Reservation::getUserId, userId);
        if (status != null) {
            wrapper.eq(Reservation::getStatus, status);
        }
        wrapper.orderByDesc(Reservation::getId);
        IPage<Reservation> result = reservationMapper.selectPage(new Page<>(page, size), wrapper);
        fillNames(result.getRecords());
        return result;
    }

    @Override
    public IPage<Reservation> page(int page, int size, String keyword, Integer status, Long bookId) {
        LambdaQueryWrapper<Reservation> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(Reservation::getStatus, status);
        }
        if (bookId != null) {
            wrapper.eq(Reservation::getBookId, bookId);
        }
        if (StrUtil.isNotBlank(keyword)) {
            String like = SqlLikeUtils.likePattern(keyword);
            List<Long> userIds = sysUserMapper.selectIdsByKeywordIgnoreDeleted(like);
            List<Long> bookIds = bookMapper.selectIdsByKeywordIgnoreDeleted(like);
            if (!userIds.isEmpty() && !bookIds.isEmpty()) {
                wrapper.and(w -> w.in(Reservation::getUserId, userIds).or().in(Reservation::getBookId, bookIds));
            } else if (!userIds.isEmpty()) {
                wrapper.in(Reservation::getUserId, userIds);
            } else if (!bookIds.isEmpty()) {
                wrapper.in(Reservation::getBookId, bookIds);
            } else {
                wrapper.eq(Reservation::getId, -1L);
            }
        }
        wrapper.orderByDesc(Reservation::getId);
        IPage<Reservation> result = reservationMapper.selectPage(new Page<>(page, size), wrapper);
        fillNames(result.getRecords());
        return result;
    }

    @Override
    public void assertBorrowAllowed(Long userId, Long bookId, int stock) {
        // 调用方已持有图书行锁，且本事务读视图在加锁之后建立：
        // 这里用普通快照读即可看到已提交的预约，不再锁预约表（避免与还书/删用户形成锁环）。
        List<Reservation> active = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getBookId, bookId)
                .in(Reservation::getStatus, ReservationStatusConstant.WAITING, ReservationStatusConstant.READY)
                .orderByAsc(Reservation::getQueueNo));
        if (active.isEmpty()) {
            return;
        }
        Reservation mine = active.stream()
                .filter(r -> Objects.equals(r.getUserId(), userId))
                .findFirst().orElse(null);
        if (mine != null) {
            if (ReservationStatusConstant.READY.equals(mine.getStatus())) {
                return;
            }
            // 前面只要还有有效预约（排队中或已到书待取）就不能插队；
            // 只统计 WAITING 会漏掉"队首已到书、第二位却先把副本借走"的场景（实测缺陷）。
            long ahead = active.stream()
                    .filter(r -> r.getQueueNo() < mine.getQueueNo())
                    .count();
            if (ahead > 0) {
                throw new BusinessException("您前面还有 " + ahead + " 位预约读者，请等待到书通知");
            }
            return;
        }
        // 非预约读者：只要还有人在排队，可借副本就必须先顺延给队首，
        // 不能因为"stock 暂时大于待取数"而插队（库存变动/并发时序会制造这个窗口）。
        long waiting = active.stream()
                .filter(r -> ReservationStatusConstant.WAITING.equals(r.getStatus()))
                .count();
        if (waiting > 0) {
            throw new BusinessException("该书已有读者排队预约，请预约排队后再借");
        }
        long ready = active.stream()
                .filter(r -> ReservationStatusConstant.READY.equals(r.getStatus()))
                .count();
        if (stock <= ready) {
            throw new BusinessException("该书可借副本已被预约读者优先占用，请预约排队后再借");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void fulfillOnBorrow(Long userId, Long bookId) {
        int rows = reservationMapper.update(null, new LambdaUpdateWrapper<Reservation>()
                .eq(Reservation::getBookId, bookId)
                .eq(Reservation::getUserId, userId)
                .in(Reservation::getStatus, ReservationStatusConstant.WAITING, ReservationStatusConstant.READY)
                .set(Reservation::getStatus, ReservationStatusConstant.FULFILLED));
        if (rows > 0) {
            operationLogService.record("RESERVE_FULFILL", "预约完成：读者借走预约图书，bookId=" + bookId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void promoteAfterReturn(Long bookId) {
        promoteNext(bookId);
    }

    @Override
    public boolean hasActiveReservation(Long bookId) {
        Long count = reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getBookId, bookId)
                .in(Reservation::getStatus, ReservationStatusConstant.WAITING, ReservationStatusConstant.READY));
        return count != null && count > 0;
    }

    @Override
    public void assertRenewAllowed(Long bookId) {
        Long count = reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getBookId, bookId)
                .in(Reservation::getStatus, ReservationStatusConstant.WAITING, ReservationStatusConstant.READY));
        if (count != null && count > 0) {
            throw new BusinessException("该图书已有读者预约，无法续借，请按时归还");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int cancelActiveByUser(Long userId) {
        List<Reservation> active = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getUserId, userId)
                .in(Reservation::getStatus, ReservationStatusConstant.WAITING, ReservationStatusConstant.READY));
        if (active.isEmpty()) {
            return 0;
        }
        int cancelled = 0;
        Set<Long> affectedBooks = new HashSet<>();
        for (Reservation r : active) {
            // 先按 READY 条件更新，再按 WAITING 更新：不能用事务开始时的快照状态判断
            // 是否释放了“待取”名额，否则并发提升后会漏掉顺延。
            int readyRows = reservationMapper.update(null, new LambdaUpdateWrapper<Reservation>()
                    .eq(Reservation::getId, r.getId())
                    .eq(Reservation::getStatus, ReservationStatusConstant.READY)
                    .set(Reservation::getStatus, ReservationStatusConstant.CANCELLED));
            boolean ready = readyRows > 0;
            if (!ready) {
                int waitingRows = reservationMapper.update(null, new LambdaUpdateWrapper<Reservation>()
                        .eq(Reservation::getId, r.getId())
                        .eq(Reservation::getStatus, ReservationStatusConstant.WAITING)
                        .set(Reservation::getStatus, ReservationStatusConstant.CANCELLED));
                if (waitingRows == 0) {
                    continue;
                }
            }
            cancelled++;
            if (ready) {
                affectedBooks.add(r.getBookId());
            }
        }
        // 本方法在用户更新/删除事务内被调用（持有用户行锁），此处不能直接锁图书行：
        // 借书是 book -> user，反向加锁会形成死锁。改为事务提交后再用新事务顺延。
        promoteAfterCommit(affectedBooks);
        if (cancelled > 0) {
            operationLogService.record("RESERVE_CANCEL", "用户失效（删除/冻结/改角色），自动取消其 "
                    + cancelled + " 个有效预约");
        }
        return cancelled;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int expireOverdue() {
        // 互斥（2026-10-24 审计修复，参照 NotificationServiceImpl.scan 既有做法）：
        // 定时任务与手动 POST /reservations/expire 并发进入时，两个事务各自 selectList 出待回收集合
        // 再逐行 UPDATE，加锁顺序不一致就可能形成 InnoDB 死锁（调用方表现为 500）。
        // 这里先挡同进程并发；跨实例并发由下面的 ORDER BY id 统一加锁顺序兜底（本项目按单实例部署）。
        if (!expiring.compareAndSet(false, true)) {
            return 0;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            List<Reservation> expired = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                    .eq(Reservation::getStatus, ReservationStatusConstant.READY)
                    .lt(Reservation::getExpireTime, now)
                    .orderByAsc(Reservation::getId));
            if (expired.isEmpty()) {
                return 0;
            }
            // 先按 book_id 排序锁住所有涉及的图书行，再逐条改预约；
            // 锁序固定为 book -> reservation，避免与借书/取消互相等待。
            Set<Long> bookIds = new TreeSet<>();
            for (Reservation r : expired) {
                if (r.getBookId() != null) {
                    bookIds.add(r.getBookId());
                }
            }
            for (Long bookId : bookIds) {
                bookMapper.lockByIdForUpdate(bookId);
            }
            int count = 0;
            Set<Long> affectedBooks = new HashSet<>();
            for (Reservation r : expired) {
                int rows = reservationMapper.update(null, new LambdaUpdateWrapper<Reservation>()
                        .eq(Reservation::getId, r.getId())
                        .eq(Reservation::getStatus, ReservationStatusConstant.READY)
                        .lt(Reservation::getExpireTime, now)
                        .set(Reservation::getStatus, ReservationStatusConstant.EXPIRED));
                if (rows > 0) {
                    count++;
                    if (r.getBookId() != null) {
                        affectedBooks.add(r.getBookId());
                    }
                }
            }
            for (Long bookId : new TreeSet<>(affectedBooks)) {
                promoteNext(bookId);
            }
            return count;
        } finally {
            expiring.set(false);
        }
    }

    /** 单次顺延的最大处理条数（取消无效读者也计入；防止异常状态下长时间占用事务） */
    private static final int MAX_PROMOTE_ITERATIONS = 1000;

    /**
     * 把队首"排队中"提升为"待取书"，直到可借库存被待取预约占满。
     * 每一步都用条件更新，定时任务与手动触发并发时同一预约最多推进一次。
     *
     * 空转保护（2026-10-23 P0 修复）：
     * RR 隔离下队首/待取计数是快照读，若另一事务在本事务快照建立后改了同一行，
     * 条件更新返回 0 行，而快照读仍看到旧状态——旧实现在此处 continue 会永久空转，
     * 占死一个 Tomcat 线程与一个 Hikari 连接。现在任何一次"没有推进"都立即返回，
     * 交给下一次调用（新事务快照）重试；并设循环上限兜底。
     */
    private void promoteNext(Long bookId) {
        if (bookId == null) {
            return;
        }
        // 必须在同一事务内锁图书行后再读库存：普通快照读可能看到并发借书扣减前的旧值，
        // 把已无副本的预约提升为“待取”。调用方若已持有该行锁，InnoDB 允许重入。
        bookMapper.lockByIdForUpdate(bookId);
        Book book = bookMapper.selectByIdForUpdate(bookId);
        if (book == null) {
            return;
        }
        int stock = book.getStock() == null ? 0 : book.getStock();
        int loops = 0;
        int maxLoops = MAX_PROMOTE_ITERATIONS;
        while (true) {
            if (++loops > maxLoops) {
                log.warn("promoteNext 达到循环上限，提前结束：bookId={}, stock={}", bookId, stock);
                return;
            }
            // 当前读（FOR UPDATE）而不是普通快照读：调用方可能在本事务锁定图书行之前
            // 已建立 RR 快照（cancel/expireOverdue/returnBook 的首条普通 SELECT），
            // 普通 selectCount/selectOne 会漏看其他事务已提交的 READY 提升，造成超发。
            List<Reservation> active = reservationMapper.selectActiveByBookForUpdate(bookId);
            int readyCount = (int) active.stream()
                    .filter(r -> ReservationStatusConstant.READY.equals(r.getStatus()))
                    .count();
            if (stock - readyCount <= 0) {
                return;
            }
            Reservation head = active.stream()
                    .filter(r -> ReservationStatusConstant.WAITING.equals(r.getStatus()))
                    .findFirst().orElse(null);
            if (head == null) {
                return;
            }
            SysUser reader = sysUserMapper.selectById(head.getUserId());
            if (reader == null) {
                // 读者已删除：取消该预约并继续检查下一位
                int rows = reservationMapper.update(null, new LambdaUpdateWrapper<Reservation>()
                        .eq(Reservation::getId, head.getId())
                        .eq(Reservation::getStatus, ReservationStatusConstant.WAITING)
                        .set(Reservation::getStatus, ReservationStatusConstant.CANCELLED));
                if (rows == 0) {
                    log.info("promoteNext 队首状态已被并发修改，结束本轮：bookId={}, reservationId={}",
                            bookId, head.getId());
                    return;
                }
                continue;
            }
            Long borrowing = borrowRecordMapper.selectCount(new LambdaQueryWrapper<BorrowRecord>()
                    .eq(BorrowRecord::getUserId, head.getUserId())
                    .eq(BorrowRecord::getBookId, bookId)
                    .eq(BorrowRecord::getStatus, BorrowStatusConstant.BORROWING));
            if (borrowing != null && borrowing > 0) {
                // 读者已通过其他入口借到该书：预约直接完成，不再占用待取名额
                int rows = reservationMapper.update(null, new LambdaUpdateWrapper<Reservation>()
                        .eq(Reservation::getId, head.getId())
                        .eq(Reservation::getStatus, ReservationStatusConstant.WAITING)
                        .set(Reservation::getStatus, ReservationStatusConstant.FULFILLED));
                if (rows == 0) {
                    log.info("promoteNext 队首状态已被并发修改，结束本轮：bookId={}, reservationId={}",
                            bookId, head.getId());
                    return;
                }
                continue;
            }
            if (!readerPolicyService.isEligibleForBorrow(reader)) {
                // 已冻结/证过期/额度满/有逾期/欠费超限：名额不能浪费在客观上借不到的读者上
                int rows = reservationMapper.update(null, new LambdaUpdateWrapper<Reservation>()
                        .eq(Reservation::getId, head.getId())
                        .eq(Reservation::getStatus, ReservationStatusConstant.WAITING)
                        .set(Reservation::getStatus, ReservationStatusConstant.CANCELLED));
                if (rows == 0) {
                    log.info("promoteNext 队首状态已被并发修改，结束本轮：bookId={}, reservationId={}",
                            bookId, head.getId());
                    return;
                }
                log.info("预约顺延跳过不合格读者：bookId={}, reservationId={}, userId={}",
                        bookId, head.getId(), head.getUserId());
                continue;
            }
            LocalDateTime now = LocalDateTime.now();
            int rows = reservationMapper.update(null, new LambdaUpdateWrapper<Reservation>()
                    .eq(Reservation::getId, head.getId())
                    .eq(Reservation::getStatus, ReservationStatusConstant.WAITING)
                    .set(Reservation::getStatus, ReservationStatusConstant.READY)
                    .set(Reservation::getReadyTime, now)
                    .set(Reservation::getExpireTime, now.plusDays(ReservationConstant.READY_HOLD_DAYS)));
            if (rows == 0) {
                // 状态被并发事务修改：快照仍会看到旧行，继续循环只会空转，必须立即结束。
                // 下一次触发（新事务快照）会从最新状态继续顺延。
                log.info("promoteNext 未推进（并发修改），结束本轮：bookId={}, reservationId={}",
                        bookId, head.getId());
                return;
            }
            // 到书即时通知（AFTER_COMMIT 落库；失败由通知扫描按 dedup_key 补齐）
            eventPublisher.publishEvent(new NotificationEvent(head.getUserId(),
                    NotificationConstant.TYPE_RESERVATION_READY, "预约到书提醒",
                    "您预约的《" + book.getTitle() + "》已到馆，请于 "
                            + now.plusDays(ReservationConstant.READY_HOLD_DAYS).toLocalDate()
                            + " 前到馆取书，逾期名额将顺延给下一位读者。",
                    NotificationConstant.BIZ_RESERVATION, head.getId(), "READY:" + head.getId()));
            // 继续循环：若有多本可借副本，可同时满足多位队首
        }
    }

    /**
     * 事务提交后再顺延。用于“用户更新/删除/冻结”这类已持有用户行锁的路径：
     * 若在事务内锁图书行，会与借书的 book -> user 锁序形成 AB-BA。
     */
    private void promoteAfterCommit(Set<Long> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) {
            return;
        }
        Set<Long> sorted = new TreeSet<>(bookIds);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runPromotionInNewTransaction(sorted);
                }
            });
        } else {
            runPromotionInNewTransaction(sorted);
        }
    }

    private void runPromotionInNewTransaction(Set<Long> bookIds) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    bookIds.forEach(this::promoteNext));
        } catch (Exception e) {
            // 顺延失败不回滚已提交的用户/预约操作；下一次还书、库存变化或定时任务会再次推进。
            log.warn("预约顺延失败，等待下一次触发：bookIds={}", bookIds, e);
        }
    }

    /** 填充书名/读者信息与当前排队位置 */
    private void fillNames(List<Reservation> reservations) {
        if (reservations.isEmpty()) {
            return;
        }
        List<Long> bookIds = reservations.stream().map(Reservation::getBookId)
                .filter(Objects::nonNull).distinct().toList();
        List<Long> userIds = reservations.stream().map(Reservation::getUserId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> bookTitleMap = bookIds.isEmpty() ? Map.of()
                : bookMapper.selectTitlesByIds(bookIds).stream()
                .collect(Collectors.toMap(m -> ((Number) m.get("id")).longValue(),
                        m -> (String) m.get("title")));
        Map<Long, Map<String, Object>> userMap = userIds.isEmpty() ? Map.of()
                : sysUserMapper.selectUsersIgnoreDeleted(userIds).stream()
                .collect(Collectors.toMap(m -> ((Number) m.get("id")).longValue(), Function.identity()));
        reservations.forEach(r -> {
            r.setBookTitle(bookTitleMap.get(r.getBookId()));
            Map<String, Object> user = userMap.get(r.getUserId());
            if (user != null) {
                r.setUserName((String) user.get("realName"));
                r.setReaderNo((String) user.get("readerNo"));
            }
            if (ReservationStatusConstant.READY.equals(r.getStatus())) {
                r.setQueuePosition(1);
            } else if (ReservationStatusConstant.WAITING.equals(r.getStatus())) {
                Integer ahead = reservationMapper.countAhead(r.getBookId(), r.getQueueNo());
                r.setQueuePosition((ahead == null ? 0 : ahead) + 1);
            }
        });
    }
}

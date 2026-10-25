package com.library.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.library.common.ResultCode;
import com.library.common.constant.BorrowStatusConstant;
import com.library.common.constant.FineConstant;
import com.library.common.constant.NotificationConstant;
import com.library.common.constant.ReservationStatusConstant;
import com.library.common.constant.RoleConstant;
import com.library.common.exception.BusinessException;
import com.library.config.NotificationProperties;
import com.library.entity.Book;
import com.library.entity.BorrowRecord;
import com.library.entity.FineRecord;
import com.library.entity.Notification;
import com.library.entity.Reservation;
import com.library.entity.SysUser;
import com.library.mapper.BookMapper;
import com.library.mapper.BorrowRecordMapper;
import com.library.mapper.FineRecordMapper;
import com.library.mapper.NotificationMapper;
import com.library.mapper.ReservationMapper;
import com.library.mapper.SysUserMapper;
import com.library.service.NotificationEmailService;
import com.library.service.NotificationService;
import com.library.service.OperationLogService;
import com.library.vo.EmailDispatchVO;
import com.library.vo.NotificationPageVO;
import com.library.vo.NotificationScanVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 站内通知服务实现
 *
 * 设计要点：
 * 1. 即时事件（预约到书/罚金生成/注册欢迎）由业务事务提交后发布，本服务落库；
 * 2. 提醒类（到期/逾期/欠费/证将到期）由扫描生成，与事件通道共用 dedup_key，
 *    因此"事件丢失"只影响时效、不会丢消息，也不会重复；
 * 3. 表不设逻辑删除，已读超保留期物理清理，保持"5 张表有 deleted"的巡检口径不变。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationMapper notificationMapper;
    private final ReservationMapper reservationMapper;
    private final BorrowRecordMapper borrowRecordMapper;
    private final FineRecordMapper fineRecordMapper;
    private final SysUserMapper sysUserMapper;
    private final BookMapper bookMapper;
    private final OperationLogService operationLogService;
    private final NotificationEmailService emailService;
    private final NotificationProperties properties;

    /** 扫描互斥：定时任务与手动触发并发时只跑一轮（dedup 本身也幂等，这里只是省资源） */
    private final AtomicBoolean scanning = new AtomicBoolean(false);

    @Override
    @Transactional(readOnly = true)
    public NotificationPageVO pageForUser(Long userId, int page, int size, boolean unreadOnly) {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId);
        if (unreadOnly) {
            wrapper.eq(Notification::getReadFlag, NotificationConstant.READ_NO);
        }
        wrapper.orderByDesc(Notification::getCreateTime).orderByDesc(Notification::getId);
        IPage<Notification> result = notificationMapper.selectPage(new Page<>(page, size), wrapper);

        NotificationPageVO vo = new NotificationPageVO();
        vo.setRecords(result.getRecords());
        vo.setTotal(result.getTotal());
        vo.setPage(result.getCurrent());
        vo.setSize(result.getSize());
        vo.setUnreadCount(unreadCount(userId));
        return vo;
    }

    @Override
    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        Long count = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getReadFlag, NotificationConstant.READ_NO));
        return count == null ? 0L : count;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long userId, Long id) {
        Notification exist = notificationMapper.selectOne(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getId, id)
                .eq(Notification::getUserId, userId));
        if (exist == null) {
            // 不区分"不存在"与"他人的"，避免通知 ID 被枚举
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "通知不存在");
        }
        if (NotificationConstant.READ_YES.equals(exist.getReadFlag())) {
            return;
        }
        notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, id)
                .eq(Notification::getUserId, userId)
                .eq(Notification::getReadFlag, NotificationConstant.READ_NO)
                .set(Notification::getReadFlag, NotificationConstant.READ_YES)
                .set(Notification::getReadTime, LocalDateTime.now()));
        operationLogService.recordBestEffort(userId, "NOTIFICATION_READ", "标记通知已读（ID " + id + "）");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int markAllRead(Long userId) {
        int rows = notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getReadFlag, NotificationConstant.READ_NO)
                .set(Notification::getReadFlag, NotificationConstant.READ_YES)
                .set(Notification::getReadTime, LocalDateTime.now()));
        if (rows > 0) {
            operationLogService.recordBestEffort(userId, "NOTIFICATION_READ_ALL",
                    "全部通知置为已读（" + rows + " 条）");
        }
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean create(Long userId, Integer type, String title, String content,
                          String bizType, Long bizId, String dedupKey) {
        if (userId == null || type == null || StrUtil.isBlank(dedupKey)) {
            return false;
        }
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type);
        n.setTitle(StrUtil.maxLength(StrUtil.blankToDefault(title, "系统通知"), 120));
        n.setContent(StrUtil.maxLength(StrUtil.blankToDefault(content, ""), 500));
        n.setBizType(bizType);
        n.setBizId(bizId);
        n.setDedupKey(StrUtil.maxLength(dedupKey, 160));
        n.setReadFlag(NotificationConstant.READ_NO);
        n.setEmailAttempts(0);
        n.setEmailStatus(initialEmailStatus(userId));
        // 待发状态不写 next_attempt_time（NULL 即"立即可投"）：
        // DATETIME 列秒级取整会让"写入即扫描"的同一秒内比较为假，导致首轮漏投
        try {
            return notificationMapper.insert(n) > 0;
        } catch (DuplicateKeyException e) {
            // 事件通道与扫描通道可能同时命中同一业务事件：唯一键兜底，静默跳过
            return false;
        }
    }

    @Override
    public NotificationScanVO scan() {
        NotificationScanVO vo = new NotificationScanVO();
        if (!scanning.compareAndSet(false, true)) {
            vo.setBusy(true);
            return vo;
        }
        try {
            vo.setCreated(generateReminders());
            vo.setPurged(purgeOldRead());
            EmailDispatchVO mail = emailService.dispatch();
            vo.setEmailed(mail.getSent());
            vo.setFailed(mail.getFailed());
            vo.setSkipped(mail.getSkipped());
        } finally {
            scanning.set(false);
        }
        return vo;
    }

    // ==================== 扫描生成 ====================

    /** 预约到书（事件通道的兜底补齐） */
    private int generateReservationReady() {
        List<Reservation> ready = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getStatus, ReservationStatusConstant.READY)
                .gt(Reservation::getExpireTime, LocalDateTime.now()));
        if (ready.isEmpty()) {
            return 0;
        }
        Map<Long, String> titles = titlesOf(ready.stream().map(Reservation::getBookId).toList());
        int created = 0;
        for (Reservation r : ready) {
            String title = StrUtil.blankToDefault(titles.get(r.getBookId()), "图书");
            boolean ok = create(r.getUserId(), NotificationConstant.TYPE_RESERVATION_READY,
                    "预约到书提醒",
                    "您预约的《" + title + "》已到馆，请于 "
                            + (r.getExpireTime() == null ? "尽快" : r.getExpireTime().toLocalDate())
                            + " 前到馆取书，逾期名额将顺延给下一位读者。",
                    NotificationConstant.BIZ_RESERVATION, r.getId(), "READY:" + r.getId());
            if (ok) {
                created++;
            }
        }
        return created;
    }

    /** 临近到期 + 逾期催还 */
    private int generateDueAndOverdue() {
        List<BorrowRecord> active = borrowRecordMapper.selectList(new LambdaQueryWrapper<BorrowRecord>()
                .eq(BorrowRecord::getStatus, BorrowStatusConstant.BORROWING));
        if (active.isEmpty()) {
            return 0;
        }
        LocalDate today = LocalDate.now();
        LocalDate dueLine = today.plusDays(properties.getDueSoonDays());
        Map<Long, String> titles = titlesOf(active.stream().map(BorrowRecord::getBookId).toList());
        Map<Long, SysUser> users = usersOf(active.stream().map(BorrowRecord::getUserId).toList());
        int created = 0;
        for (BorrowRecord b : active) {
            if (b.getDueDate() == null || !canNotify(users.get(b.getUserId()))) {
                continue;
            }
            String title = StrUtil.blankToDefault(titles.get(b.getBookId()), "图书");
            if (b.getDueDate().isBefore(today)) {
                long days = today.toEpochDay() - b.getDueDate().toEpochDay();
                if (create(b.getUserId(), NotificationConstant.TYPE_OVERDUE, "逾期催还",
                        "您借阅的《" + title + "》已逾期 " + days + " 天（应还 " + b.getDueDate()
                                + "），请尽快归还，逾期期间将按规则计收罚金。",
                        NotificationConstant.BIZ_BORROW, b.getId(), "OVERDUE:" + b.getId())) {
                    created++;
                }
            } else if (!b.getDueDate().isAfter(dueLine)) {
                if (create(b.getUserId(), NotificationConstant.TYPE_DUE_SOON, "到期提醒",
                        "您借阅的《" + title + "》将于 " + b.getDueDate() + " 到期，请及时归还或办理续借。",
                        NotificationConstant.BIZ_BORROW, b.getId(), "DUE_SOON:" + b.getId())) {
                    created++;
                }
            }
        }
        return created;
    }

    /** 未缴罚金提醒 */
    private int generateUnpaidFines() {
        List<FineRecord> fines = fineRecordMapper.selectList(new LambdaQueryWrapper<FineRecord>()
                .eq(FineRecord::getStatus, FineConstant.STATUS_UNPAID));
        Map<Long, SysUser> users = usersOf(fines.stream().map(FineRecord::getUserId).toList());
        int created = 0;
        for (FineRecord f : fines) {
            if (!canNotify(users.get(f.getUserId()))) {
                continue;
            }
            if (create(f.getUserId(), NotificationConstant.TYPE_FINE_UNPAID, "罚金缴纳提醒",
                    "您有一笔未缴罚金 ¥" + money(f.getAmount())
                            + (StrUtil.isBlank(f.getRemark()) ? "" : "（" + f.getRemark() + "）")
                            + "，请到馆缴纳后再借阅。",
                    NotificationConstant.BIZ_FINE, f.getId(), "FINE:" + f.getId())) {
                created++;
            }
        }
        return created;
    }

    /** 借阅证将到期提醒 */
    private int generateCardExpiring() {
        LocalDate today = LocalDate.now();
        LocalDate line = today.plusDays(properties.getCardExpiringDays());
        List<SysUser> readers = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRole, RoleConstant.READER)
                .eq(SysUser::getStatus, 1)
                .isNotNull(SysUser::getValidUntil)
                .ge(SysUser::getValidUntil, today)
                .le(SysUser::getValidUntil, line));
        int created = 0;
        for (SysUser u : readers) {
            if (create(u.getId(), NotificationConstant.TYPE_CARD_EXPIRING, "借阅证到期提醒",
                    "您的借阅证将于 " + u.getValidUntil() + " 到期，到期后将无法借书、续借与预约，请到馆办理续期。",
                    NotificationConstant.BIZ_USER, u.getId(),
                    "CARD_EXPIRING:" + u.getId() + ":" + u.getValidUntil())) {
                created++;
            }
        }
        return created;
    }

    private int generateReminders() {
        return generateReservationReady() + generateDueAndOverdue()
                + generateUnpaidFines() + generateCardExpiring();
    }

    private int purgeOldRead() {
        LocalDateTime before = LocalDateTime.now().minusDays(Math.max(properties.getRetentionDays(), 1));
        return notificationMapper.delete(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getReadFlag, NotificationConstant.READ_YES)
                .lt(Notification::getCreateTime, before));
    }

    // ==================== 工具 ====================

    private Integer initialEmailStatus(Long userId) {
        if (!properties.getEmail().isEnabled()) {
            return NotificationConstant.EMAIL_SKIPPED;
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || StrUtil.isBlank(user.getEmail())
                || Integer.valueOf(0).equals(user.getEmailNotify())) {
            return NotificationConstant.EMAIL_SKIPPED;
        }
        return NotificationConstant.EMAIL_PENDING;
    }

    private Map<Long, String> titlesOf(List<Long> bookIds) {
        List<Long> ids = bookIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (ids.isEmpty()) {
            return new HashMap<>();
        }
        List<Book> books = bookMapper.selectList(new LambdaQueryWrapper<Book>().in(Book::getId, ids));
        Map<Long, String> map = new HashMap<>();
        books.forEach(b -> map.put(b.getId(), b.getTitle()));
        return map;
    }

    private Map<Long, SysUser> usersOf(List<Long> userIds) {
        List<Long> ids = userIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (ids.isEmpty()) {
            return new HashMap<>();
        }
        Map<Long, SysUser> map = new HashMap<>();
        sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>().in(SysUser::getId, ids))
                .forEach(u -> map.put(u.getId(), u));
        return map;
    }

    /** 冻结/已删除账号不生成提醒：读者端无法登录，消息只会成为噪声 */
    private boolean canNotify(SysUser user) {
        return user != null && !Integer.valueOf(0).equals(user.getStatus());
    }

    private String money(BigDecimal amount) {
        return (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}

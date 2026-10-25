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
import com.library.common.constant.RoleConstant;
import com.library.common.exception.BusinessException;
import com.library.entity.Book;
import com.library.entity.BorrowRecord;
import com.library.entity.FineRecord;
import com.library.entity.SysUser;
import com.library.event.NotificationEvent;
import com.library.mapper.BookMapper;
import com.library.mapper.BorrowRecordMapper;
import com.library.mapper.FineRecordMapper;
import com.library.mapper.SysUserMapper;
import com.library.service.BorrowService;
import com.library.service.OperationLogService;
import com.library.service.ReaderPolicyService;
import com.library.service.ReservationService;
import com.library.utils.SqlLikeUtils;
import com.library.utils.UserContext;
import com.library.vo.ReturnResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 借阅核心服务实现
 * 关键点（对应 docs/03、docs/05）：
 * 1. 借书 = 规则校验 → 条件更新扣库存(AND stock>0) → 插入流水 → borrowed_count+1，整体事务
 * 2. 条件更新影响行数为 0 即并发竞争失败，事务回滚并提示重试（严禁"先查库存再 set"）
 * 3. 逾期天数统一走 SQL 层 DATEDIFF 计算
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BorrowServiceImpl implements BorrowService {

    private final BorrowRecordMapper borrowRecordMapper;
    private final BookMapper bookMapper;
    private final SysUserMapper sysUserMapper;
    private final FineRecordMapper fineRecordMapper;
    private final OperationLogService operationLogService;
    private final ReservationService reservationService;
    private final ReaderPolicyService readerPolicyService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BorrowRecord borrow(Long userId, Long bookId) {
        if (userId == null || bookId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "读者与图书不能为空");
        }
        // 1. 先锁图书行，再建立本事务的读视图：
        //    之后对读者 / 借阅流水 / 预约的普通读都发生在加锁之后，能看到并发 reserve / borrow
        //    已提交的结果；同书并发已被图书行锁串行化，因此无需在预约表、流水表上再加锁
        //    （避免与"还书先锁流水再锁图书"形成 AB-BA 死锁）。
        bookMapper.lockByIdForUpdate(bookId);
        Book book = bookMapper.selectByIdForUpdate(bookId);
        if (book == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "图书不存在");
        }
        // 2. 读者校验（状态 / 角色 / 借书铁律）
        // 锁用户行：与“改角色/冻结/删除”的 user 行锁互斥，避免校验后被并发改角色，
        // 导致非读者仍生成借阅记录；锁序 book -> user，与还书/借书更新顺序一致。
        SysUser reader = sysUserMapper.selectByIdForUpdate(userId);
        if (reader == null || !RoleConstant.READER.equals(reader.getRole())) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "读者不存在");
        }
        if (reader.getStatus() != null && reader.getStatus() == 0) {
            throw new BusinessException("读者账号已冻结，无法借书");
        }
        readerPolicyService.assertCardValid(reader, "借书");
        readerPolicyService.assertBorrowLimitAvailable(reader, "借书");
        readerPolicyService.assertNoOverdue(reader, "借书");
        readerPolicyService.assertUnpaidWithinLimit(reader, "借书");
        // 3. 同一读者同一本书只能有一条在借记录（预约接口早已拒绝，借书接口此前漏判，
        //    实测同一本书可借 3 次，导致读者额度被一本占用、borrow_count 虚高）。
        Long sameBookBorrowing = borrowRecordMapper.selectCount(new LambdaQueryWrapper<BorrowRecord>()
                .eq(BorrowRecord::getUserId, userId)
                .eq(BorrowRecord::getBookId, bookId)
                .eq(BorrowRecord::getStatus, BorrowStatusConstant.BORROWING));
        if (sameBookBorrowing != null && sameBookBorrowing > 0) {
            throw new BusinessException("该读者已借阅此书且尚未归还，请勿重复借阅");
        }
        // 4. 预约优先：待取副本先满足队首，排队未到号/无预约者不得插队
        reservationService.assertBorrowAllowed(userId, bookId,
                book.getStock() == null ? 0 : book.getStock());

        // 5. 条件更新扣库存（防超借核心：影响行数为 0 = 并发竞争失败，事务回滚）
        int stockRows = bookMapper.update(null, new LambdaUpdateWrapper<Book>()
                .eq(Book::getId, bookId)
                .gt(Book::getStock, 0)
                .setSql("stock = stock - 1, borrow_count = borrow_count + 1"));
        if (stockRows == 0) {
            throw new BusinessException("库存不足或图书信息已变化，请重试");
        }

        // 3. 读者在借数 +1（条件更新同时约束读者级额度上限，避免并发下突破：
        //    预检通过后若额度已满，本次更新影响行数为 0，随事务整体回滚）
        int userRows = sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .eq(SysUser::getStatus, 1)
                .eq(SysUser::getRole, RoleConstant.READER)
                .apply("borrowed_count < borrow_limit")
                .setSql("borrowed_count = borrowed_count + 1"));
        if (userRows == 0) {
            throw new BusinessException("借阅额度不足或账号状态异常，请刷新后重试");
        }

        // 4. 插入借阅流水
        BorrowRecord record = new BorrowRecord();
        record.setUserId(userId);
        record.setBookId(bookId);
        LocalDate today = LocalDate.now();
        record.setBorrowDate(today);
        record.setDueDate(today.plusDays(FineConstant.BORROW_DAYS));
        record.setStatus(BorrowStatusConstant.BORROWING);
        record.setRenewCount(0);
        borrowRecordMapper.insert(record);
        // 预约完成：读者借走自己排队/待取的图书后，预约不再占用队首名额
        reservationService.fulfillOnBorrow(userId, bookId);

        operationLogService.record("BORROW", String.format("为读者[%s %s]办理借书：《%s》，应还日期 %s",
                StrUtil.blankToDefault(reader.getReaderNo(), "未设置"), reader.getRealName(), book.getTitle(), record.getDueDate()));
        record.setBookTitle(book.getTitle());
        return record;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReturnResultVO returnBook(Long recordId) {
        BorrowRecord record = borrowRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "借阅记录不存在");
        }
        if (record.getStatus() == null || record.getStatus().intValue() != BorrowStatusConstant.BORROWING) {
            throw new BusinessException("该图书已归还/已赔偿，请勿重复操作");
        }
        LocalDate today = LocalDate.now();
        // 条件更新防重复还书（并发双击只成功一次）
        int rows = borrowRecordMapper.update(null, new LambdaUpdateWrapper<BorrowRecord>()
                .eq(BorrowRecord::getId, recordId)
                .eq(BorrowRecord::getStatus, BorrowStatusConstant.BORROWING)
                .set(BorrowRecord::getStatus, BorrowStatusConstant.RETURNED)
                .set(BorrowRecord::getReturnDate, today));
        if (rows == 0) {
            throw new BusinessException("该图书已归还，请勿重复操作");
        }
        // 库存 +1、读者在借数 -1（GREATEST 防并发下减成负数）
        // 影响行数校验：回补 SQL 会被 @TableLogic 追加 deleted=0，若图书已被逻辑删除（如"在借禁删"守卫
        // 被并发绕过），影响行数为 0 且 stock 不回补——账实将永久少 1 册，必须显式告警
        int stockRows = bookMapper.update(null, new LambdaUpdateWrapper<Book>()
                .eq(Book::getId, record.getBookId())
                .setSql("stock = stock + 1"));
        if (stockRows == 0) {
            // 回补 SQL 会被 @TableLogic 追加 deleted=0，若图书已被逻辑删除则影响行数为 0、库存不回补。
            // 此前仅 log.error 后照常返回"还书成功"，导致流水已置已还但库存永久少 1 册（账实不符）。
            // 现改为抛业务异常并回滚整个还书事务：流水状态退回在借，由管理员先恢复图书再还书，
            // 保证"接口成功 ⇒ 数据一致"这一契约。
            log.error("库存回补失败：图书已不存在或被逻辑删除，bookId={}, recordId={}，已回滚本次还书",
                    record.getBookId(), record.getId());
            throw new BusinessException("还书失败：该图书已被删除，库存无法回补，请先恢复图书后再办理归还");
        }
        int userRows = sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, record.getUserId())
                .setSql("borrowed_count = GREATEST(borrowed_count - 1, 0)"));
        if (userRows == 0) {
            // 用户已被逻辑删除时，更新会被 @TableLogic 追加 deleted=0 而影响 0 行。
            // 与图书已删的处理保持一致：抛异常回滚整笔还书，避免流水已还但计数永久漂移。
            log.error("在借数回补失败：借阅用户已不存在或被逻辑删除，userId={}, recordId={}，已回滚本次还书",
                    record.getUserId(), record.getId());
            throw new BusinessException("还书失败：借阅用户已被删除，无法回补在借数，请先恢复用户后再办理归还");
        }
        // 还书后触发预约队首到书：有可借副本时把最早的"排队中"提升为"待取书"（7 天）
        reservationService.promoteAfterReturn(record.getBookId());

        // 逾期罚金：SQL 层 DATEDIFF 计算（检查点 C，不在 Java 内存逐条遍历）
        ReturnResultVO vo = new ReturnResultVO();
        Long overdueDays = borrowRecordMapper.selectOverdueDays(recordId, today);
        int days = overdueDays == null ? 0 : overdueDays.intValue();
        if (days > 0) {
            // 金额固定两位小数（与库内 DECIMAL(10,2)、前端展示口径一致）
            BigDecimal amount = BigDecimal.valueOf(days)
                    .multiply(BigDecimal.valueOf(FineConstant.FINE_PER_DAY))
                    .setScale(2, RoundingMode.HALF_UP);
            FineRecord fine = new FineRecord();
            fine.setUserId(record.getUserId());
            fine.setBorrowId(record.getId());
            fine.setAmount(amount);
            fine.setType(FineConstant.TYPE_OVERDUE);
            fine.setStatus(FineConstant.STATUS_UNPAID);
            fine.setRemark("逾期" + days + "天 × " + FineConstant.FINE_PER_DAY + "元/天");
            fineRecordMapper.insert(fine);
            // 罚金生成即时通知（AFTER_COMMIT 落库；失败由扫描按 dedup_key 补齐）
            Book finedBook = bookMapper.selectById(record.getBookId());
            eventPublisher.publishEvent(new NotificationEvent(record.getUserId(),
                    NotificationConstant.TYPE_FINE_UNPAID, "罚金缴纳提醒",
                    "您借阅的《" + (finedBook == null ? "图书" : finedBook.getTitle()) + "》已逾期 " + days
                            + " 天，产生未缴罚金 ¥" + amount.toPlainString() + "，请到馆缴纳后再借阅。",
                    NotificationConstant.BIZ_FINE, fine.getId(), "FINE:" + fine.getId()));
            vo.setOverdueDays(days);
            vo.setFineAmount(amount);
        } else {
            vo.setOverdueDays(0);
        }

        record.setStatus(BorrowStatusConstant.RETURNED);
        record.setReturnDate(today);
        // 回填读者姓名/证号/书名，保证还书响应 record 的展示字段完整（与列表口径一致）
        fillNames(List.of(record));
        vo.setRecord(record);
        // 审计文案与 BORROW 对齐（含读者与书名），便于"谁还了什么书"的直接追溯（M22-1）
        operationLogService.record("RETURN", String.format("为读者[%s %s]办理还书：《%s》，应还日期 %s",
                record.getReaderNo(), record.getUserName(), record.getBookTitle(), record.getDueDate()));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BorrowRecord renew(Long recordId) {
        BorrowRecord record = borrowRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "借阅记录不存在");
        }
        // 越权防护：只有读者才校验归属；馆员与系统管理员均可代办续借
        // （接口注解早已放行 ADMIN，旧实现只认 LIBRARIAN，导致管理员续借必 403）
        if (RoleConstant.READER.equals(UserContext.getRole())
                && !Objects.equals(record.getUserId(), UserContext.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        if (record.getStatus() == null || record.getStatus().intValue() != BorrowStatusConstant.BORROWING) {
            throw new BusinessException("该书不在借状态，无法续借");
        }
        if (record.getDueDate().isBefore(LocalDate.now())) {
            throw new BusinessException("该书已逾期，无法续借，请尽快归还并缴纳罚金");
        }
        if (record.getRenewCount() != null && record.getRenewCount() >= 1) {
            throw new BusinessException("每册图书仅可续借一次");
        }
        SysUser reader = sysUserMapper.selectById(record.getUserId());
        if (reader == null) {
            throw new BusinessException("读者不存在，无法续借");
        }
        readerPolicyService.assertCardValid(reader, "续借");
        // 欠费联动（与 borrow() 同一口径）：未缴罚金超过读者额度上限时不允许续借，
        // 否则"欠着罚金还能延长借期"会成为真实系统里明显的规则漏洞。
        readerPolicyService.assertUnpaidWithinLimit(reader, "续借");
        // 预约联动：被预约的图书禁止续借，否则队首会被无限期拖延（与真实图书馆"预约催还"一致）
        reservationService.assertRenewAllowed(record.getBookId());
        // 条件更新：应还日期 +30 天（自原应还日期起算）、续借次数置 1
        int rows = borrowRecordMapper.update(null, new LambdaUpdateWrapper<BorrowRecord>()
                .eq(BorrowRecord::getId, recordId)
                .eq(BorrowRecord::getStatus, BorrowStatusConstant.BORROWING)
                .lt(BorrowRecord::getRenewCount, 1)
                .setSql("due_date = DATE_ADD(due_date, INTERVAL " + FineConstant.RENEW_DAYS + " DAY), renew_count = 1"));
        if (rows == 0) {
            throw new BusinessException("续借失败，请刷新后重试");
        }
        record.setDueDate(record.getDueDate().plusDays(FineConstant.RENEW_DAYS));
        record.setRenewCount(1);
        // 回填展示字段（与借书/还书口径一致），并供审计文案使用（M22-1）
        fillNames(List.of(record));
        operationLogService.record("RENEW", String.format("为读者[%s %s]办理续借：《%s》，新应还日期 %s",
                record.getReaderNo(), record.getUserName(), record.getBookTitle(), record.getDueDate()));
        return record;
    }

    @Override
    public IPage<BorrowRecord> page(int page, int size, String keyword, Integer status, Long userId,
                                    LocalDate startDate, LocalDate endDate) {
        LambdaQueryWrapper<BorrowRecord> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(BorrowRecord::getStatus, status);
        }
        if (userId != null) {
            wrapper.eq(BorrowRecord::getUserId, userId);
        }
        // 借出日期范围筛选（docs/03：状态/日期筛选）
        if (startDate != null) {
            wrapper.ge(BorrowRecord::getBorrowDate, startDate);
        }
        if (endDate != null) {
            wrapper.le(BorrowRecord::getBorrowDate, endDate);
        }
        if (StrUtil.isNotBlank(keyword)) {
            appendKeywordCondition(wrapper, keyword);
        }
        wrapper.orderByDesc(BorrowRecord::getId);
        IPage<BorrowRecord> result = borrowRecordMapper.selectPage(new Page<>(page, size), wrapper);
        fillNames(result.getRecords());
        return result;
    }

    @Override
    public IPage<BorrowRecord> myPage(int page, int size, Integer status, Long currentUserId, boolean history) {
        LambdaQueryWrapper<BorrowRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BorrowRecord::getUserId, currentUserId);
        if (history) {
            // 历史页只排除"在借"，含已还与已赔偿；由服务端分页，避免前端全量拉取
            wrapper.ne(BorrowRecord::getStatus, BorrowStatusConstant.BORROWING);
        } else if (status != null) {
            wrapper.eq(BorrowRecord::getStatus, status);
        }
        wrapper.orderByDesc(BorrowRecord::getId);
        IPage<BorrowRecord> result = borrowRecordMapper.selectPage(new Page<>(page, size), wrapper);
        fillNames(result.getRecords());
        return result;
    }

    /**
     * 关键词匹配：读者姓名/证号 或 书名（参数化查询拼 id 列表，杜绝字符串拼接 SQL 注入）。
     *
     * 走"忽略逻辑删除"的自定义 SQL：历史流水里的读者/图书可能已被软删，
     * 若按 MP 默认查询（自动附加 deleted=0），用旧证号/旧书名检索会命中 0 条——
     * 与列表页"历史仍可见"的口径矛盾。此处保持与 fillNames 一致：历史检索不过滤已删。
     */
    private void appendKeywordCondition(LambdaQueryWrapper<BorrowRecord> wrapper, String keyword) {
        String like = SqlLikeUtils.likePattern(keyword);
        List<Long> userIds = sysUserMapper.selectIdsByKeywordIgnoreDeleted(like);
        List<Long> bookIds = bookMapper.selectIdsByKeywordIgnoreDeleted(like);
        if (!userIds.isEmpty() && !bookIds.isEmpty()) {
            wrapper.and(w -> w.in(BorrowRecord::getUserId, userIds).or().in(BorrowRecord::getBookId, bookIds));
        } else if (!userIds.isEmpty()) {
            wrapper.in(BorrowRecord::getUserId, userIds);
        } else if (!bookIds.isEmpty()) {
            wrapper.in(BorrowRecord::getBookId, bookIds);
        } else {
            wrapper.eq(BorrowRecord::getId, -1L);
        }
    }

    /** 填充读者姓名/证号/书名展示字段 */
    private void fillNames(List<BorrowRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        List<Long> userIds = records.stream().map(BorrowRecord::getUserId).filter(java.util.Objects::nonNull).distinct().toList();
        List<Long> bookIds = records.stream().map(BorrowRecord::getBookId).filter(java.util.Objects::nonNull).distinct().toList();
        // 用户信息用忽略逻辑删除的查询：读者被删除后历史记录仍可回显姓名与证号
        Map<Long, Map<String, Object>> userMap = userIds.isEmpty() ? Map.of()
                : sysUserMapper.selectUsersIgnoreDeleted(userIds).stream()
                .collect(Collectors.toMap(m -> ((Number) m.get("id")).longValue(), Function.identity()));
        // 书名用自定义 SQL 查询（忽略逻辑删除）：图书被删除后历史记录仍可回显书名
        Map<Long, String> bookTitleMap = bookIds.isEmpty() ? Map.of()
                : bookMapper.selectTitlesByIds(bookIds).stream()
                .collect(Collectors.toMap(m -> ((Number) m.get("id")).longValue(), m -> (String) m.get("title")));
        records.forEach(record -> {
            Map<String, Object> user = userMap.get(record.getUserId());
            if (user != null) {
                record.setUserName((String) user.get("realName"));
                record.setReaderNo((String) user.get("readerNo"));
            }
            record.setBookTitle(bookTitleMap.get(record.getBookId()));
        });
    }
}
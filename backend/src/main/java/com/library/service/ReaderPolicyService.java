package com.library.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.library.common.constant.BorrowStatusConstant;
import com.library.common.constant.FineConstant;
import com.library.common.exception.BusinessException;
import com.library.entity.BorrowRecord;
import com.library.entity.FineRecord;
import com.library.entity.SysUser;
import com.library.mapper.BorrowRecordMapper;
import com.library.mapper.FineRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * 读者借阅资格规则：借阅证有效期、读者级额度、逾期与最大欠费额度。
 * 借书、续借、预约共用同一口径，避免三处规则漂移。
 */
@Service
@RequiredArgsConstructor
public class ReaderPolicyService {

    private final FineRecordMapper fineRecordMapper;
    private final BorrowRecordMapper borrowRecordMapper;

    /** 借阅证过期后允许还书/缴费，但不能再借书、续借或预约 */
    public void assertCardValid(SysUser reader, String action) {
        if (reader == null || reader.getValidUntil() == null) {
            return;
        }
        if (reader.getValidUntil().isBefore(LocalDate.now())) {
            throw new BusinessException("借阅证已过期（有效期至 " + reader.getValidUntil()
                    + "），无法" + action + "，请先办理续期");
        }
    }

    /** 读者级可借额度：borrow_limit = 0 表示不可借；已在借数达到上限时拒绝 */
    public void assertBorrowLimitAvailable(SysUser reader, String action) {
        if (reader == null) {
            return;
        }
        int limit = reader.getBorrowLimit() == null ? FineConstant.MAX_BORROW : reader.getBorrowLimit();
        if (limit <= 0) {
            throw new BusinessException("该读者当前不可借（可借额度为 0），无法" + action);
        }
        if (reader.getBorrowedCount() != null && reader.getBorrowedCount() >= limit) {
            throw new BusinessException("该读者借阅已达上限（" + limit + "册），请先归还");
        }
    }

    /** 存在逾期未还图书时拒绝新的借阅/预约（当天到期不算逾期，与 DATEDIFF 口径一致） */
    public void assertNoOverdue(SysUser reader, String action) {
        if (reader == null) {
            return;
        }
        Long overdue = borrowRecordMapper.selectCount(new LambdaQueryWrapper<BorrowRecord>()
                .eq(BorrowRecord::getUserId, reader.getId())
                .eq(BorrowRecord::getStatus, BorrowStatusConstant.BORROWING)
                .lt(BorrowRecord::getDueDate, LocalDate.now()));
        if (overdue != null && overdue > 0) {
            throw new BusinessException("该读者有逾期未还图书，请先归还");
        }
    }

    /**
     * 未缴罚金校验：
     * - max_unpaid = 0（默认）沿用“有任何未缴罚金即禁止”的旧口径；
     * - max_unpaid > 0 时，按未缴金额合计与上限比较，允许小额欠费在限额内继续借阅。
     */
    public void assertUnpaidWithinLimit(SysUser reader, String action) {
        if (reader == null) {
            return;
        }
        BigDecimal maxUnpaid = reader.getMaxUnpaid() == null ? BigDecimal.ZERO : reader.getMaxUnpaid();
        if (maxUnpaid.signum() == 0) {
            Long unpaidCount = fineRecordMapper.selectCount(new LambdaQueryWrapper<FineRecord>()
                    .eq(FineRecord::getUserId, reader.getId())
                    .eq(FineRecord::getStatus, FineConstant.STATUS_UNPAID));
            if (unpaidCount != null && unpaidCount > 0) {
                throw new BusinessException("该读者有未缴罚金，无法" + action + "，请先缴清");
            }
            return;
        }
        BigDecimal unpaidAmount = unpaidAmount(reader.getId());
        if (unpaidAmount.compareTo(maxUnpaid) > 0) {
            throw new BusinessException("未缴罚金 ¥"
                    + unpaidAmount.setScale(2, RoundingMode.HALF_UP).toPlainString()
                    + " 超过该读者上限 ¥" + maxUnpaid.setScale(2, RoundingMode.HALF_UP).toPlainString()
                    + "，无法" + action + "，请先缴纳部分罚金");
        }
    }

    /**
     * 资格总判定（供预约队列顺延时批量过滤）：
     * 账号正常、证未过期、额度未满、无逾期未还、欠费未超限。
     */
    public boolean isEligibleForBorrow(SysUser reader) {
        if (reader == null || (reader.getStatus() != null && reader.getStatus() == 0)) {
            return false;
        }
        if (reader.getValidUntil() != null && reader.getValidUntil().isBefore(LocalDate.now())) {
            return false;
        }
        int limit = reader.getBorrowLimit() == null ? FineConstant.MAX_BORROW : reader.getBorrowLimit();
        if (limit <= 0 || (reader.getBorrowedCount() != null && reader.getBorrowedCount() >= limit)) {
            return false;
        }
        Long overdue = borrowRecordMapper.selectCount(new LambdaQueryWrapper<BorrowRecord>()
                .eq(BorrowRecord::getUserId, reader.getId())
                .eq(BorrowRecord::getStatus, BorrowStatusConstant.BORROWING)
                .lt(BorrowRecord::getDueDate, LocalDate.now()));
        if (overdue != null && overdue > 0) {
            return false;
        }
        BigDecimal maxUnpaid = reader.getMaxUnpaid() == null ? BigDecimal.ZERO : reader.getMaxUnpaid();
        if (maxUnpaid.signum() == 0) {
            Long unpaidCount = fineRecordMapper.selectCount(new LambdaQueryWrapper<FineRecord>()
                    .eq(FineRecord::getUserId, reader.getId())
                    .eq(FineRecord::getStatus, FineConstant.STATUS_UNPAID));
            return unpaidCount == null || unpaidCount == 0;
        }
        return unpaidAmount(reader.getId()).compareTo(maxUnpaid) <= 0;
    }

    private BigDecimal unpaidAmount(Long userId) {
        BigDecimal amount = fineRecordMapper.sumAmountByUserAndStatus(userId, FineConstant.STATUS_UNPAID);
        return amount == null ? BigDecimal.ZERO : amount;
    }
}

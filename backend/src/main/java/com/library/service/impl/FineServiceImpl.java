package com.library.service.impl;

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
import com.library.dto.CompensateDTO;
import com.library.entity.*;
import com.library.event.NotificationEvent;
import com.library.mapper.BookMapper;
import com.library.mapper.BorrowRecordMapper;
import com.library.mapper.FineRecordMapper;
import com.library.mapper.SysUserMapper;
import com.library.service.FineService;
import com.library.service.OperationLogService;
import com.library.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 罚金服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FineServiceImpl implements FineService {

    private final FineRecordMapper fineRecordMapper;
    private final BorrowRecordMapper borrowRecordMapper;
    private final BookMapper bookMapper;
    private final SysUserMapper sysUserMapper;
    private final OperationLogService operationLogService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public IPage<FineRecord> page(int page, int size, Integer status, Long userId) {
        // 读者角色只能查看自己的罚金（后端强制，防 IDOR）
        if (RoleConstant.READER.equals(UserContext.getRole())) {
            userId = UserContext.getUserId();
        }
        LambdaQueryWrapper<FineRecord> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(FineRecord::getStatus, status);
        }
        if (userId != null) {
            wrapper.eq(FineRecord::getUserId, userId);
        }
        wrapper.orderByDesc(FineRecord::getId);
        IPage<FineRecord> result = fineRecordMapper.selectPage(new Page<>(page, size), wrapper);
        fillNames(result.getRecords());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pay(Long id) {
        // 条件更新：仅"未缴"状态可标记，防重复收款（成功路径保持原子条件更新，无需预查）。
        // 2026-10-23：收款仅限馆员/管理员（控制器注解同步收紧）。读者自助把罚金置为已缴
        // 会自行解除"欠费禁借"闸门，且系统没有支付凭据，等于业务规则失效。
        int rows = fineRecordMapper.update(null, new LambdaUpdateWrapper<FineRecord>()
                .eq(FineRecord::getId, id)
                .eq(FineRecord::getStatus, FineConstant.STATUS_UNPAID)
                .set(FineRecord::getStatus, FineConstant.STATUS_PAID)
                .set(FineRecord::getPaidTime, LocalDateTime.now()));
        if (rows == 0) {
            // R20-2：区分不存在 404 / 已缴 业务提示；仅失败路径补一次存在性查询
            FineRecord exist = fineRecordMapper.selectById(id);
            if (exist == null) {
                throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "罚金记录不存在");
            }
            throw new BusinessException("罚金已缴纳，请勿重复操作");
        }
        // 资金留痕：此前只记记录 ID，无法回答"收了多少、谁的"。金额取库内值（落库后的真值）
        FineRecord paid = fineRecordMapper.selectById(id);
        operationLogService.record("PAY", String.format("罚金收款：记录ID %s，读者ID %s，金额 %s 元",
                id, paid == null ? "-" : paid.getUserId(),
                paid == null || paid.getAmount() == null ? "-" : paid.getAmount().toPlainString()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void compensate(CompensateDTO dto) {
        if (dto.getType() == null
                || (dto.getType().intValue() != FineConstant.TYPE_DAMAGE
                    && dto.getType().intValue() != FineConstant.TYPE_LOST)) {
            throw new BusinessException("赔偿类型不合法");
        }
        BorrowRecord record = borrowRecordMapper.selectById(dto.getRecordId());
        if (record == null) {
            // R19-5：存在性与状态分离判断，与 renew/return 的 404 语义对齐（原实现合并为 500）
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "借阅记录不存在");
        }
        if (record.getStatus() == null
                || record.getStatus().intValue() != BorrowStatusConstant.BORROWING) {
            throw new BusinessException("该记录不在借状态，无法赔偿登记");
        }
        // 记录状态：在借 → 已赔偿（条件更新防重复提交）
        int rows = borrowRecordMapper.update(null, new LambdaUpdateWrapper<BorrowRecord>()
                .eq(BorrowRecord::getId, dto.getRecordId())
                .eq(BorrowRecord::getStatus, BorrowStatusConstant.BORROWING)
                .set(BorrowRecord::getStatus, BorrowStatusConstant.COMPENSATED));
        if (rows == 0) {
            throw new BusinessException("该记录已处理，请刷新后重试");
        }
        // 读者在借数 -1；库存不恢复（破损/遗失，docs/03 约定）
        int userRows = sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, record.getUserId())
                .setSql("borrowed_count = GREATEST(borrowed_count - 1, 0)"));
        if (userRows == 0) {
            log.error("在借数回补失败：赔偿记录的用户已不存在或被逻辑删除，userId={}, recordId={}，已回滚本次赔偿",
                    record.getUserId(), record.getId());
            throw new BusinessException("赔偿登记失败：借阅用户已被删除，无法回补在借数，请先恢复用户后再办理");
        }

        FineRecord fine = new FineRecord();
        fine.setUserId(record.getUserId());
        fine.setBorrowId(record.getId());
        fine.setAmount(dto.getAmount());
        fine.setType(dto.getType());
        fine.setStatus(FineConstant.STATUS_UNPAID);
        fine.setRemark(dto.getRemark());
        fineRecordMapper.insert(fine);

        // 赔偿登记即时通知（AFTER_COMMIT 落库；失败由扫描按 dedup_key 补齐）
        Book compensatedBook = record.getBookId() == null ? null : bookMapper.selectById(record.getBookId());
        eventPublisher.publishEvent(new NotificationEvent(record.getUserId(),
                NotificationConstant.TYPE_FINE_UNPAID, "赔偿缴纳提醒",
                "您借阅的《" + (compensatedBook == null ? "图书" : compensatedBook.getTitle()) + "》已登记"
                        + (dto.getType().intValue() == FineConstant.TYPE_LOST ? "遗失" : "破损") + "赔偿 ¥"
                        + dto.getAmount().toPlainString() + "，请到馆缴纳。",
                NotificationConstant.BIZ_FINE, fine.getId(), "FINE:" + fine.getId()));

        operationLogService.record("COMPENSATE", String.format("赔偿登记：记录ID %s，金额 %s 元（类型 %s）",
                dto.getRecordId(), dto.getAmount(),
                dto.getType().intValue() == FineConstant.TYPE_LOST ? "遗失" : "破损"));
    }

    /** 填充读者姓名/证号/书名展示字段（经 borrow_record 关联图书） */
    private void fillNames(List<FineRecord> fines) {
        if (fines.isEmpty()) {
            return;
        }
        List<Long> userIds = fines.stream().map(FineRecord::getUserId).filter(java.util.Objects::nonNull).distinct().toList();
        List<Long> borrowIds = fines.stream().map(FineRecord::getBorrowId).filter(java.util.Objects::nonNull).distinct().toList();
        // 用户信息用忽略逻辑删除的查询：读者被删除后罚金记录仍可回显姓名与证号
        Map<Long, Map<String, Object>> userMap = userIds.isEmpty() ? Map.of()
                : sysUserMapper.selectUsersIgnoreDeleted(userIds).stream()
                .collect(Collectors.toMap(m -> ((Number) m.get("id")).longValue(), Function.identity()));
        // 批量解析 bookId → bookTitle（先查借阅记录，再批量查图书）
        Map<Long, String> bookTitleMap = new java.util.HashMap<>();
        if (!borrowIds.isEmpty()) {
            Map<Long, Long> borrow2book = borrowRecordMapper.selectBatchIds(borrowIds).stream()
                    .filter(rec -> rec.getBookId() != null)
                    .collect(Collectors.toMap(BorrowRecord::getId, BorrowRecord::getBookId, (a, b) -> a));
            List<Long> bookIds = borrow2book.values().stream().distinct().toList();
            // 书名用自定义 SQL 查询（忽略逻辑删除）：图书被删除后罚金记录仍可回显书名
            Map<Long, String> titleMap = bookIds.isEmpty() ? Map.of()
                    : bookMapper.selectTitlesByIds(bookIds).stream()
                    .collect(Collectors.toMap(m -> ((Number) m.get("id")).longValue(), m -> (String) m.get("title")));
            borrow2book.forEach((borrowId, bookId) -> bookTitleMap.put(borrowId, titleMap.get(bookId)));
        }
        fines.forEach(fine -> {
            Map<String, Object> user = userMap.get(fine.getUserId());
            if (user != null) {
                fine.setUserName((String) user.get("realName"));
                fine.setReaderNo((String) user.get("readerNo"));
            }
            fine.setBookTitle(bookTitleMap.get(fine.getBorrowId()));
        });
    }
}
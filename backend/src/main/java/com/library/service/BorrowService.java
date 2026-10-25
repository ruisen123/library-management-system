package com.library.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.library.entity.BorrowRecord;
import com.library.vo.ReturnResultVO;

/**
 * 借阅核心服务：借书 / 还书 / 续借 / 记录查询
 */
public interface BorrowService {

    /** 办理借书（事务 + 条件更新扣库存防超借） */
    BorrowRecord borrow(Long userId, Long bookId);

    /** 办理还书（逾期罚金在 SQL 层按 DATEDIFF 计算） */
    ReturnResultVO returnBook(Long recordId);

    /** 续借（读者仅限本人记录；每册限一次，逾期不可续借） */
    BorrowRecord renew(Long recordId);

    /** 借阅记录分页查询（图书管理员：全量流水，支持状态/关键词/借出日期范围筛选） */
    IPage<BorrowRecord> page(int page, int size, String keyword, Integer status, Long userId,
                             java.time.LocalDate startDate, java.time.LocalDate endDate);

    /** 我的借阅（读者：后端强制过滤本人记录，防 IDOR；history=true 时排除在借，供历史页服务端分页） */
    IPage<BorrowRecord> myPage(int page, int size, Integer status, Long currentUserId, boolean history);
}
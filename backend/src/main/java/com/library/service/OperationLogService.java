package com.library.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.library.entity.OperationLog;

/**
 * 操作日志服务：关键写操作留痕（借/还/续/罚金/登录等）
 */
public interface OperationLogService {

    /** 记录一条日志（操作人取当前登录用户，IP 自动获取） */
    void record(String operation, String detail);

    /** 记录一条日志（指定操作人） */
    void record(Long userId, String operation, String detail);

    /**
     * 尽力而为地记录日志：用于登录失败、巡检动作等不属于业务事务的旁路留痕。
     * 失败只写应用日志，不阻断调用方。
     */
    void recordBestEffort(Long userId, String operation, String detail);

    /** 分页查询日志（关键词匹配操作人/操作类型/详情） */
    IPage<OperationLog> page(int page, int size, String keyword);
}
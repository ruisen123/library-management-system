package com.library.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.library.entity.OperationLog;
import com.library.entity.SysUser;
import com.library.mapper.OperationLogMapper;
import com.library.mapper.SysUserMapper;
import com.library.service.OperationLogService;
import com.library.utils.IpUtils;
import com.library.utils.SqlLikeUtils;
import com.library.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 操作日志服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperationLogServiceImpl implements OperationLogService {

    private final OperationLogMapper operationLogMapper;
    private final SysUserMapper sysUserMapper;

    /**
     * 业务写操作的审计日志：加入调用方事务，与业务同提交/同回滚。
     *
     * 为什么不使用 REQUIRES_NEW：
     * 业务事务已占用一个连接，REQUIRES_NEW 会再申请第二个连接；本系统连接池为 10，
     * 10 个并发慢写会互相等待审计事务的连接，实测出现 Hikari 30 秒超时。
     * 审计日志是借还/罚金等关键操作的证据，选择"审计优先"：日志写不进去就让业务回滚，
     * 保证"接口成功 ⇔ 业务与审计都已落库"。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void record(String operation, String detail) {
        doRecord(UserContext.getUserId(), operation, detail);
    }

    /** 业务写操作的审计日志（指定操作人），语义同上：加入当前业务事务。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void record(Long userId, String operation, String detail) {
        doRecord(userId, operation, detail);
    }

    /**
     * 尽力而为的旁路日志：登录失败、巡检动作等没有业务事务可加入的场景。
     * 失败只写应用日志，不影响登录/巡检结果。
     */
    @Override
    public void recordBestEffort(Long userId, String operation, String detail) {
        try {
            doRecord(userId, operation, detail);
        } catch (Exception e) {
            log.error("审计日志写入失败（旁路留痕，调用方不受影响）：operation={}, userId={}, err={}",
                    operation, userId, e.getMessage());
        }
    }

    /** 实际写库；由调用方决定是业务事务内失败回滚，还是旁路失败仅告警 */
    private void doRecord(Long userId, String operation, String detail) {
        OperationLog log = new OperationLog();
        log.setUserId(userId);
        log.setOperation(operation);
        // detail/ip 来自不可信输入（如未登录即可提交的登录名、伪造请求头），
        // 剥离控制字符（含换行/回车），防止向审计日志注入伪造多行记录
        log.setDetail(sanitize(detail));
        log.setIp(sanitize(IpUtils.getClientIp()));
        operationLogMapper.insert(log);
    }

    /** 剥离控制字符（Cntrl 类：换行/回车/制表符等），替换为空格，保证审计内容单行可信 */
    private static String sanitize(String text) {
        if (text == null) {
            return null;
        }
        return text.replaceAll("\\p{Cntrl}", " ");
    }

    @Override
    public IPage<OperationLog> page(int page, int size, String keyword) {
        LambdaQueryWrapper<OperationLog> wrapper = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(keyword)) {
            // 关键词匹配 操作详情 / 操作类型 / 操作人姓名（先按姓名解析出用户 ID）
            // 关键字按字面量匹配（%/_ 已转义，显式 ESCAPE，2026-10-24 审计修复）；
            // 列名 = operation_log.detail/operation、sys_user.real_name（docs/04_数据库设计.sql:44,189-190）
            String like = SqlLikeUtils.likePattern(keyword);
            List<Long> userIds = sysUserMapper.selectList(
                            new LambdaQueryWrapper<SysUser>()
                                    .select(SysUser::getId)
                                    .apply(SqlLikeUtils.likeCondition("real_name"), like))
                    .stream().map(SysUser::getId).toList();
            if (userIds.isEmpty()) {
                wrapper.and(w -> w.apply(SqlLikeUtils.likeCondition("detail"), like)
                        .or().apply(SqlLikeUtils.likeCondition("operation"), like));
            } else {
                wrapper.and(w -> w.apply(SqlLikeUtils.likeCondition("detail"), like)
                        .or().apply(SqlLikeUtils.likeCondition("operation"), like)
                        .or().in(OperationLog::getUserId, userIds));
            }
        }
        wrapper.orderByDesc(OperationLog::getId);
        IPage<OperationLog> result = operationLogMapper.selectPage(new Page<>(page, size), wrapper);
        // 填充操作人姓名
        List<Long> userIds = result.getRecords().stream()
                .map(OperationLog::getUserId).filter(java.util.Objects::nonNull).distinct().toList();
        if (!userIds.isEmpty()) {
            // 复用"忽略逻辑删除"的批量查询：操作人被删除后日志仍可回显姓名（与借阅/罚金/公告口径一致）
            Map<Long, String> nameMap = sysUserMapper.selectUsersIgnoreDeleted(userIds).stream()
                    .collect(Collectors.toMap(m -> ((Number) m.get("id")).longValue(),
                            m -> (String) m.get("realName")));
            result.getRecords().forEach(log -> log.setUserName(
                    log.getUserId() == null ? null : nameMap.get(log.getUserId())));
        }
        return result;
    }
}
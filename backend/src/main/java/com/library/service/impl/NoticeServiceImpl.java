package com.library.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.library.common.ResultCode;
import com.library.common.constant.RoleConstant;
import com.library.common.exception.BusinessException;
import com.library.dto.NoticeDTO;
import com.library.entity.Notice;
import com.library.mapper.NoticeMapper;
import com.library.mapper.SysUserMapper;
import com.library.service.NoticeService;
import com.library.service.OperationLogService;
import com.library.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 公告服务实现
 */
@Service
@RequiredArgsConstructor
public class NoticeServiceImpl implements NoticeService {

    private final NoticeMapper noticeMapper;
    private final SysUserMapper sysUserMapper;
    private final OperationLogService operationLogService;

    @Override
    public IPage<Notice> page(int page, int size, Integer status, Boolean onlyPublished) {
        LambdaQueryWrapper<Notice> wrapper = new LambdaQueryWrapper<>();
        // 非系统管理员仅可查看"已发布"公告，防越权读取已撤回公告（docs/03：读者只读已发布，撤回态仅发布者可见）
        if (Boolean.TRUE.equals(onlyPublished) || !RoleConstant.ADMIN.equals(UserContext.getRole())) {
            wrapper.eq(Notice::getStatus, 1);
        } else if (status != null) {
            wrapper.eq(Notice::getStatus, status);
        }
        wrapper.orderByDesc(Notice::getId);
        IPage<Notice> result = noticeMapper.selectPage(new Page<>(page, size), wrapper);
        fillPublisherName(result.getRecords());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(NoticeDTO dto) {
        Notice notice = new Notice();
        notice.setTitle(dto.getTitle());
        notice.setContent(dto.getContent());
        notice.setPublisherId(UserContext.getUserId());
        notice.setStatus(1);
        noticeMapper.insert(notice);
        operationLogService.record("NOTICE_ADD", "发布公告[" + dto.getTitle() + "]");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, NoticeDTO dto) {
        Notice exist = noticeMapper.selectById(id);
        if (exist == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "公告不存在");
        }
        Integer status = dto.getStatus() == null ? exist.getStatus() : dto.getStatus();
        if (status != 0 && status != 1) {
            throw new BusinessException("公告状态不合法");
        }
        exist.setTitle(dto.getTitle());
        exist.setContent(dto.getContent());
        exist.setStatus(status);
        int rows = noticeMapper.updateById(exist);
        if (rows == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "公告不存在");
        }
        operationLogService.record("NOTICE_UPDATE", (status == 1 ? "发布公告ID " : "撤回公告ID ") + id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        int rows = noticeMapper.deleteById(id);
        if (rows == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "公告不存在");
        }
        operationLogService.record("NOTICE_DELETE", "删除公告ID " + id);
    }

    /** 填充发布人姓名（忽略逻辑删除：发布人被删除后公告仍可回显姓名，与借阅/罚金口径一致） */
    private void fillPublisherName(List<Notice> notices) {
        if (notices.isEmpty()) {
            return;
        }
        List<Long> publisherIds = notices.stream().map(Notice::getPublisherId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (publisherIds.isEmpty()) {
            return;
        }
        Map<Long, String> nameMap = sysUserMapper.selectUsersIgnoreDeleted(publisherIds).stream()
                .collect(Collectors.toMap(m -> ((Number) m.get("id")).longValue(), m -> (String) m.get("realName")));
        notices.forEach(notice -> notice.setPublisherName(nameMap.get(notice.getPublisherId())));
    }
}
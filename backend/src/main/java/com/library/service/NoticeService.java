package com.library.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.library.dto.NoticeDTO;
import com.library.entity.Notice;

/**
 * 公告服务
 */
public interface NoticeService {

    /** 分页查询（onlyPublished 为 true 时只返回已发布，供读者端使用） */
    IPage<Notice> page(int page, int size, Integer status, Boolean onlyPublished);

    /** 发布公告（发布人取当前登录用户） */
    void add(NoticeDTO dto);

    /** 编辑公告 / 发布/撤回切换 */
    void update(Long id, NoticeDTO dto);

    /** 删除公告（逻辑删除） */
    void delete(Long id);
}
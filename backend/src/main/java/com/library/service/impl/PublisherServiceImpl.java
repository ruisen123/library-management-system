package com.library.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.library.common.ResultCode;
import com.library.common.exception.BusinessException;
import com.library.entity.Book;
import com.library.entity.Publisher;
import com.library.mapper.BookMapper;
import com.library.mapper.PublisherMapper;
import com.library.service.OperationLogService;
import com.library.service.PublisherService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 出版社服务实现
 */
@Service
@RequiredArgsConstructor
public class PublisherServiceImpl implements PublisherService {

    private final PublisherMapper publisherMapper;
    private final BookMapper bookMapper;
    private final OperationLogService operationLogService;

    @Override
    public List<Publisher> list() {
        return publisherMapper.selectList(
                new LambdaQueryWrapper<Publisher>().orderByAsc(Publisher::getId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(Publisher publisher) {
        if (StrUtil.isBlank(publisher.getName())) {
            throw new BusinessException("出版社名称不能为空");
        }
        if (publisher.getName().length() > 100) {
            throw new BusinessException("出版社名称长度不能超过 100 个字符");
        }
        // 列宽校验：contact(50)/address(200) 超长在 MySQL 严格模式下会抛错并被吞成"系统繁忙"
        if (publisher.getContact() != null && publisher.getContact().length() > 50) {
            throw new BusinessException("联系方式长度不能超过 50 个字符");
        }
        if (publisher.getAddress() != null && publisher.getAddress().length() > 200) {
            throw new BusinessException("出版社地址长度不能超过 200 个字符");
        }
        Long count = publisherMapper.selectCount(
                new LambdaQueryWrapper<Publisher>().eq(Publisher::getName, publisher.getName()));
        if (count > 0) {
            throw new BusinessException("出版社名称已存在");
        }
        publisher.setId(null);
        // 服务端掌控字段显式重置（与 Category.add 同口径）：防止客户端注入 deleted/createTime
        publisher.setDeleted(0);
        publisher.setCreateTime(null);
        publisher.setUpdateTime(null);
        publisherMapper.insert(publisher);
        operationLogService.record("PUBLISHER_ADD", "新增出版社[" + publisher.getName() + "]");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Publisher publisher) {
        if (publisher.getId() == null || publisherMapper.selectById(publisher.getId()) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "出版社不存在");
        }
        if (StrUtil.isBlank(publisher.getName())) {
            throw new BusinessException("出版社名称不能为空");
        }
        if (publisher.getName().length() > 100) {
            throw new BusinessException("出版社名称长度不能超过 100 个字符");
        }
        // 列宽校验：contact(50)/address(200) 超长在 MySQL 严格模式下会抛错并被吞成"系统繁忙"
        if (publisher.getContact() != null && publisher.getContact().length() > 50) {
            throw new BusinessException("联系方式长度不能超过 50 个字符");
        }
        if (publisher.getAddress() != null && publisher.getAddress().length() > 200) {
            throw new BusinessException("出版社地址长度不能超过 200 个字符");
        }
        Long count = publisherMapper.selectCount(
                new LambdaQueryWrapper<Publisher>()
                        .eq(Publisher::getName, publisher.getName())
                        .ne(Publisher::getId, publisher.getId()));
        if (count > 0) {
            throw new BusinessException("出版社名称已存在");
        }
        // 显式 set 提交全部可编辑字段：updateById 默认跳过 null 字段，会导致清空"联系方式/地址"无法写入
        int rows = publisherMapper.update(null, new LambdaUpdateWrapper<Publisher>()
                .eq(Publisher::getId, publisher.getId())
                .set(Publisher::getName, publisher.getName())
                .set(Publisher::getContact, publisher.getContact())
                .set(Publisher::getAddress, publisher.getAddress()));
        if (rows == 0 && publisherMapper.selectById(publisher.getId()) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "出版社不存在");
        }
        operationLogService.record("PUBLISHER_UPDATE", "编辑出版社ID " + publisher.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Publisher exist = publisherMapper.selectById(id);
        if (exist == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "出版社不存在");
        }
        Long bookCount = bookMapper.selectCount(
                new LambdaQueryWrapper<Book>().eq(Book::getPublisherId, id));
        if (bookCount > 0) {
            throw new BusinessException("该出版社名下存在图书，禁止删除");
        }
        publisherMapper.deleteById(id);
        operationLogService.record("PUBLISHER_DELETE", "删除出版社ID " + id);
    }
}
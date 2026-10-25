package com.library.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.library.common.ResultCode;
import com.library.common.exception.BusinessException;
import com.library.entity.Book;
import com.library.entity.Category;
import com.library.mapper.BookMapper;
import com.library.mapper.CategoryMapper;
import com.library.service.CategoryService;
import com.library.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 图书分类服务实现
 */
@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private final CategoryMapper categoryMapper;
    private final BookMapper bookMapper;
    private final OperationLogService operationLogService;

    @Override
    public List<Category> list() {
        return categoryMapper.selectList(
                new LambdaQueryWrapper<Category>()
                        .orderByAsc(Category::getSort)
                        .orderByAsc(Category::getId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(Category category) {
        if (StrUtil.isBlank(category.getName())) {
            throw new BusinessException("分类名称不能为空");
        }
        if (category.getName().length() > 50) {
            throw new BusinessException("分类名称长度不能超过 50 个字符");
        }
        // 名称唯一性校验（@TableLogic 自动排除已删除数据，逻辑删除后同名可重建）
        Long count = categoryMapper.selectCount(
                new LambdaQueryWrapper<Category>().eq(Category::getName, category.getName()));
        if (count > 0) {
            throw new BusinessException("分类名称已存在");
        }
        category.setId(null);
        // 服务端掌控字段显式重置：实体直接绑定请求体，客户端可注入 deleted（实测可创建"创建即已删除"的幽灵数据）
        // 与 createTime（实测可伪造创建时间），故在此统一归零/置空，由数据库默认值补齐
        category.setDeleted(0);
        category.setCreateTime(null);
        category.setUpdateTime(null);
        if (category.getSort() == null) {
            category.setSort(0);
        }
        categoryMapper.insert(category);
        operationLogService.record("CATEGORY_ADD", "新增分类[" + category.getName() + "]");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Category category) {
        // 保存已加载实体：sort 为 NOT NULL 列，显式 set 时须用原值兜底（省略 sort 即保留原值），
        // 否则 SET sort=NULL 会触发约束异常（第八轮曾因此引入回归）
        Category exist = categoryMapper.selectById(category.getId());
        if (category.getId() == null || exist == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "分类不存在");
        }
        if (StrUtil.isBlank(category.getName())) {
            throw new BusinessException("分类名称不能为空");
        }
        if (category.getName().length() > 50) {
            throw new BusinessException("分类名称长度不能超过 50 个字符");
        }
        Long count = categoryMapper.selectCount(
                new LambdaQueryWrapper<Category>()
                        .eq(Category::getName, category.getName())
                        .ne(Category::getId, category.getId()));
        if (count > 0) {
            throw new BusinessException("分类名称已存在");
        }
        // 显式 set 仅提交可编辑字段：updateById 会把请求体携带的字段一并回写
        // （实测 createTime 可被改为任意值），与 Publisher 的更新写法保持一致
        int rows = categoryMapper.update(null, new LambdaUpdateWrapper<Category>()
                .eq(Category::getId, category.getId())
                .set(Category::getName, category.getName())
                .set(Category::getSort, category.getSort() == null ? exist.getSort() : category.getSort()));
        if (rows == 0 && categoryMapper.selectById(category.getId()) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "分类不存在");
        }
        operationLogService.record("CATEGORY_UPDATE", "编辑分类ID " + category.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Category exist = categoryMapper.selectById(id);
        if (exist == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "分类不存在");
        }
        // 分类下存在未删除图书时禁止删除（外键 + 业务友好提示）
        Long bookCount = bookMapper.selectCount(
                new LambdaQueryWrapper<Book>().eq(Book::getCategoryId, id));
        if (bookCount > 0) {
            throw new BusinessException("该分类下存在图书，禁止删除");
        }
        categoryMapper.deleteById(id);
        operationLogService.record("CATEGORY_DELETE", "删除分类ID " + id);
    }
}
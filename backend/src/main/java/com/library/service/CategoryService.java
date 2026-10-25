package com.library.service;

import com.library.entity.Category;

import java.util.List;

/**
 * 图书分类服务
 */
public interface CategoryService {

    /** 分类列表（按 sort 升序） */
    List<Category> list();

    /** 新增分类 */
    void add(Category category);

    /** 编辑分类 */
    void update(Category category);

    /** 删除分类（逻辑删除；分类下存在图书时禁止删除） */
    void delete(Long id);
}
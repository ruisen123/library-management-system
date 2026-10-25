package com.library.service;

import com.library.entity.Publisher;

import java.util.List;

/**
 * 出版社服务
 */
public interface PublisherService {

    /** 出版社列表（按 ID 升序） */
    List<Publisher> list();

    /** 新增出版社 */
    void add(Publisher publisher);

    /** 编辑出版社 */
    void update(Publisher publisher);

    /** 删除出版社（逻辑删除；名下存在图书时禁止删除） */
    void delete(Long id);
}
package com.library.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.library.dto.BookDTO;
import com.library.dto.BookImportRow;
import com.library.entity.Book;
import com.library.vo.BookImportResultVO;

import java.util.List;

/**
 * 图书档案服务
 */
public interface BookService {

    /** 分页检索（关键词匹配书名/作者/ISBN；可按分类过滤、只看有库存；sort=popular 按累计借阅次数） */
    IPage<Book> page(int page, int size, String keyword, Long categoryId, Boolean onlyStock, String sort);

    /** 图书详情（含分类/出版社名称） */
    Book getById(Long id);

    /** 新增图书（校验 ISBN 唯一、库存 ≤ 总册数） */
    void add(BookDTO dto);

    /** 编辑图书（校验 ISBN 唯一、库存自洽：总册数 ≥ 库存 且 总册数 - 库存 ≥ 在借册数） */
    void update(Long id, BookDTO dto);

    /** 删除图书（在借中/有有效预约时禁止删除） */
    void delete(Long id);

    /** Excel 批量导入：逐行校验，合法行入库、失败行返回明细 */
    BookImportResultVO importBooks(List<BookImportRow> rows);
}
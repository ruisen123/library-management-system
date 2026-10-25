package com.library.dto;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 图书批量导入行（Excel 模板列顺序与表头固定）
 *
 * 说明：出版日期用字符串接收，兼容 Excel 的文本单元格；也接受 yyyy/M/d、yyyy.M.d，
 *       解析失败会作为该行错误返回，不会中断整批导入。
 */
@Data
public class BookImportRow {

    @ExcelProperty(value = "ISBN", index = 0)
    private String isbn;

    @ExcelProperty(value = "书名", index = 1)
    private String title;

    @ExcelProperty(value = "作者", index = 2)
    private String author;

    @ExcelProperty(value = "分类", index = 3)
    private String categoryName;

    @ExcelProperty(value = "出版社", index = 4)
    private String publisherName;

    @ExcelProperty(value = "出版日期", index = 5)
    private String publishDate;

    @ExcelProperty(value = "定价", index = 6)
    private BigDecimal price;

    @ExcelProperty(value = "总册数", index = 7)
    private Integer totalStock;

    @ExcelProperty(value = "可借册数", index = 8)
    private Integer stock;

    @ExcelProperty(value = "馆藏位置", index = 9)
    private String location;

    @ExcelProperty(value = "简介", index = 10)
    private String description;
}

package com.library.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 图书新增/编辑请求参数
 */
@Data
public class BookDTO {

    /** ISBN（唯一），格式：13 位数字，或 10 位数字（末位可为 X） */
    @NotBlank(message = "ISBN 不能为空")
    @Pattern(regexp = "^(?:[0-9]{13}|[0-9]{9}[0-9X])$", message = "ISBN 格式不正确（13 位数字或 10 位数字/X）")
    private String isbn;

    /** 书名 */
    @NotBlank(message = "书名不能为空")
    @Size(max = 100, message = "书名不能超过 100 个字符")
    private String title;

    /** 作者（多人以逗号分隔） */
    @NotBlank(message = "作者不能为空")
    @Size(max = 100, message = "作者不能超过 100 个字符")
    private String author;

    /** 分类外键 */
    @NotNull(message = "请选择分类")
    private Long categoryId;

    /** 出版社外键 */
    @NotNull(message = "请选择出版社")
    private Long publisherId;

    /** 出版日期 */
    private LocalDate publishDate;

    /** 定价（破损/遗失赔偿基准） */
    @DecimalMin(value = "0", message = "定价不能为负数")
    @Max(value = 1000000, message = "定价不能超过 1000000 元")
    private BigDecimal price;

    /** 馆藏总册数 */
    @NotNull(message = "总册数不能为空")
    @Min(value = 0, message = "总册数不能为负数")
    @Max(value = 100000, message = "总册数不能超过 100000 册")
    private Integer totalStock;

    /** 当前可借册数 */
    @NotNull(message = "可借册数不能为空")
    @Min(value = 0, message = "可借册数不能为负数")
    @Max(value = 100000, message = "可借册数不能超过 100000 册")
    private Integer stock;

    /** 馆藏位置（书架号） */
    @Size(max = 50, message = "馆藏位置不能超过 50 个字符")
    private String location;

    /** 封面图片地址（扩展预留） */
    @Size(max = 255, message = "封面地址不能超过 255 个字符")
    private String cover;

    /** 内容简介 */
    @Size(max = 2000, message = "内容简介不能超过 2000 个字符")
    private String description;
}
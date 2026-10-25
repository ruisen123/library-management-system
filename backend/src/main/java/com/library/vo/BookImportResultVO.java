package com.library.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 图书批量导入结果：总行数 / 成功 / 失败 + 失败明细
 */
@Data
public class BookImportResultVO {

    private int total;
    private int success;
    private int failed;
    private List<String> errors = new ArrayList<>();
}

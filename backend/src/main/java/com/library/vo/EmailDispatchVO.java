package com.library.vo;

import lombok.Data;

/**
 * 邮件投递单轮结果
 */
@Data
public class EmailDispatchVO {
    private int sent;
    private int failed;
    private int skipped;
}

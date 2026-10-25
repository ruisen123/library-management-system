package com.library.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 破损/遗失赔偿登记请求参数
 */
@Data
public class CompensateDTO {

    /** 在借的借阅记录 ID */
    @NotNull(message = "借阅记录不能为空")
    private Long recordId;

    /** 赔偿金额（元） */
    @NotNull(message = "赔偿金额不能为空")
    @DecimalMin(value = "0.01", message = "赔偿金额必须大于 0")
    @Max(value = 1000000, message = "赔偿金额不能超过 1000000 元")
    @Digits(integer = 8, fraction = 2, message = "赔偿金额最多保留两位小数")
    private BigDecimal amount;

    /** 类型：2破损赔偿 3遗失赔偿 */
    @NotNull(message = "赔偿类型不能为空")
    private Integer type;

    /** 备注 */
    @Size(max = 200, message = "备注不能超过 200 个字符")
    private String remark;
}
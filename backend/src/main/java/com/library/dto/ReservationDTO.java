package com.library.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 预约请求参数（读者只能预约，不能指定他人）
 */
@Data
public class ReservationDTO {

    @NotNull(message = "图书不能为空")
    private Long bookId;
}

package com.library.common.exception;

import com.library.common.ResultCode;
import lombok.Getter;

/**
 * 业务异常：业务校验失败时抛出，由全局异常处理器统一转为 Result 返回
 */
@Getter
public class BusinessException extends RuntimeException {

    private final Integer code;

    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.FAIL.getCode();
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMsg());
        this.code = resultCode.getCode();
    }
}

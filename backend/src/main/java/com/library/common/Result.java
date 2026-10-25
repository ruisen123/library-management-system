package com.library.common;

import lombok.Data;

/**
 * 统一返回结果封装
 * 前端约定：code=200 表示成功，其余为失败；data 为业务数据
 *
 * @param <T> 业务数据类型
 */
@Data
public class Result<T> {

    /** 业务状态码：200 成功 */
    private Integer code;

    /** 提示信息 */
    private String msg;

    /** 业务数据 */
    private T data;

    public Result() {}

    public Result(Integer code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    /** 成功（无数据） */
    public static <T> Result<T> ok() {
        return new Result<>(200, "操作成功", null);
    }

    /** 成功（带数据） */
    public static <T> Result<T> ok(T data) {
        return new Result<>(200, "操作成功", data);
    }

    /** 成功（自定义提示 + 数据） */
    public static <T> Result<T> ok(String msg, T data) {
        return new Result<>(200, msg, data);
    }

    /** 失败（默认错误码 500） */
    public static <T> Result<T> fail(String msg) {
        return new Result<>(500, msg, null);
    }

    /** 失败（自定义错误码） */
    public static <T> Result<T> fail(Integer code, String msg) {
        return new Result<>(code, msg, null);
    }
}

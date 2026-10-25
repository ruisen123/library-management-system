package com.library.common;

import lombok.Getter;

/**
 * 业务状态码枚举
 */
@Getter
public enum ResultCode {

    SUCCESS(200, "操作成功"),
    FAIL(500, "操作失败"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "没有权限执行该操作"),
    NOT_FOUND(404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不被支持"),
    PARAM_ERROR(400, "参数错误"),
    PAYLOAD_TOO_LARGE(413, "请求体过大"),
    UNSUPPORTED_MEDIA_TYPE(415, "请求内容类型不被支持");

    private final Integer code;
    private final String msg;

    ResultCode(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}

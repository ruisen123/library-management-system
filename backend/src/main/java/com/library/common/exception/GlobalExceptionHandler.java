package com.library.common.exception;

import com.library.common.Result;
import com.library.common.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器：统一捕获异常并返回 Result，避免错误堆栈直接暴露给前端
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：返回自定义提示信息 */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常：{}", e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /** 参数校验异常：返回第一条字段错误信息 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidException(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String msg = fieldError != null ? fieldError.getDefaultMessage() : "参数校验失败";
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), msg);
    }

    /** 请求体 JSON 格式错误：返回明确的参数提示，而非笼统的“系统繁忙” */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败：{}", e.getMessage());
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), "请求参数格式错误");
    }

    /** 查询参数类型绑定失败（如 page=abc）：统一按参数错误处理 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型错误：{}", e.getMessage());
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), "参数格式错误");
    }

    /** 未匹配到任何接口：统一返回 Result 结构，避免 Spring 默认错误格式破坏前端契约 */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResource(NoResourceFoundException e) {
        return Result.fail(ResultCode.NOT_FOUND.getCode(), "接口不存在");
    }

    /** 请求方法不被支持（如用 GET 访问仅支持 POST 的接口）：返回明确的 405 提示，避免落到笼统“系统繁忙” */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return Result.fail(ResultCode.METHOD_NOT_ALLOWED.getCode(), ResultCode.METHOD_NOT_ALLOWED.getMsg());
    }

    /** 请求内容类型不被支持（如用 text/plain 提交 JSON 接口）：返回明确的 415 提示，而非 500「系统繁忙」 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public Result<Void> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.warn("请求内容类型不被支持：{}", e.getMessage());
        return Result.fail(ResultCode.UNSUPPORTED_MEDIA_TYPE.getCode(), ResultCode.UNSUPPORTED_MEDIA_TYPE.getMsg());
    }

    /**
     * 唯一约束冲突：并发下同一登录名/证号/ISBN 同时提交时，数据库唯一索引兜底拦截，
     * 转化为友好提示（应用层“先查后插”已覆盖常规场景，此处兜住极小概率的并发竞态）
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一约束冲突：{}", e.getMessage());
        return Result.fail(ResultCode.FAIL.getCode(), "数据已存在，请勿重复提交");
    }

    /** 上传文件超过 max-file-size：明确提示"文件过大"，不再落到"系统繁忙" */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过限制：{}", e.getMessage());
        return Result.fail(ResultCode.PAYLOAD_TOO_LARGE.getCode(),
                "上传文件过大，请压缩后重试（单文件不超过 5MB）");
    }

    /** multipart 请求缺少 file part：提示选择文件，而非 500 */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public Result<Void> handleMissingPart(MissingServletRequestPartException e) {
        log.warn("multipart 缺少请求部分：{}", e.getMessage());
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), "请选择要上传的文件");
    }

    /** 其他 multipart 解析失败：按参数错误处理，避免误报系统故障 */
    @ExceptionHandler(MultipartException.class)
    public Result<Void> handleMultipart(MultipartException e) {
        log.warn("multipart 请求解析失败：{}", e.getMessage());
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), "上传请求格式错误，请重新选择文件");
    }

    /** 兜底异常：记录日志，返回通用提示（不泄露内部细节） */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.fail(ResultCode.FAIL.getCode(), "系统繁忙，请稍后重试");
    }
}

package com.library.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 统一响应封装契约测试（前端约定：code=200 成功，其余失败）。
 */
@DisplayName("Result：统一响应封装契约")
class ResultTest {

    @Test
    @DisplayName("ok() 返回 200 + 固定文案 + data 为 null")
    void okWithoutData() {
        Result<Void> r = Result.ok();
        assertEquals(200, r.getCode());
        assertEquals("操作成功", r.getMsg());
        assertNull(r.getData());
    }

    @Test
    @DisplayName("ok(data) 携带业务数据")
    void okWithData() {
        Result<List<String>> r = Result.ok(List.of("a", "b"));
        assertEquals(200, r.getCode());
        assertEquals(List.of("a", "b"), r.getData());
    }

    @Test
    @DisplayName("ok(msg, data) 可自定义提示")
    void okWithCustomMsg() {
        Result<Integer> r = Result.ok("已创建", 42);
        assertEquals(200, r.getCode());
        assertEquals("已创建", r.getMsg());
        assertEquals(42, r.getData());
    }

    @Test
    @DisplayName("fail(msg) 默认错误码 500，且不携带数据")
    void failDefaultsTo500() {
        Result<Void> r = Result.fail("库存不足");
        assertEquals(500, r.getCode());
        assertEquals("库存不足", r.getMsg());
        assertNull(r.getData());
    }

    @Test
    @DisplayName("fail(code, msg) 可指定业务错误码")
    void failWithCustomCode() {
        Result<Void> r = Result.fail(401, "未登录");
        assertEquals(401, r.getCode());
        assertEquals("未登录", r.getMsg());
        assertNull(r.getData());
    }
}

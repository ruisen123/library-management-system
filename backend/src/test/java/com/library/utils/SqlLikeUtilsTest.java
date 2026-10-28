package com.library.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LIKE 通配符转义单元测试。
 * 直接测纯静态方法，不启动 Spring、不连数据库。
 */
@DisplayName("SqlLikeUtils：LIKE 通配符转义")
class SqlLikeUtilsTest {

    @Test
    @DisplayName("escape 把 % 变成字面量，不再作为通配符")
    void escapeNeutralizesPercent() {
        assertEquals("!%", SqlLikeUtils.escape("%"));
        assertEquals("a!%b", SqlLikeUtils.escape("a%b"));
    }

    @Test
    @DisplayName("escape 把 _ 变成字面量，不再匹配任意单字符")
    void escapeNeutralizesUnderscore() {
        assertEquals("!_", SqlLikeUtils.escape("_"));
        assertEquals("a!_b", SqlLikeUtils.escape("a_b"));
    }

    @Test
    @DisplayName("escape 先转义转义符自身，否则可用 ! 绕过")
    void escapeNeutralizesEscapeCharFirst() {
        assertEquals("!!", SqlLikeUtils.escape("!"));
        assertEquals("!!!%", SqlLikeUtils.escape("!%"));
    }

    @Test
    @DisplayName("escape 对 null / 空串原样返回")
    void escapeKeepsNullOrEmpty() {
        assertNull(SqlLikeUtils.escape(null));
        assertEquals("", SqlLikeUtils.escape(""));
    }

    @Test
    @DisplayName("likePattern 两侧加通配符，内部关键字已转义")
    void likePatternWrapsAndEscapes() {
        assertEquals("%abc%", SqlLikeUtils.likePattern("abc"));
        assertEquals("%a!%b%", SqlLikeUtils.likePattern("a%b"));
    }

    @Test
    @DisplayName("likePattern 对 null / 空串不拼通配符（拼成 %% 等于全表命中）")
    void likePatternDoesNotWrapNullOrEmpty() {
        assertNull(SqlLikeUtils.likePattern(null));
        assertEquals("", SqlLikeUtils.likePattern(""));
    }

    @Test
    @DisplayName("likeCondition 显式声明 ESCAPE，不依赖数据库默认转义符")
    void likeConditionDeclaresEscape() {
        assertEquals("title LIKE {0} ESCAPE '!'", SqlLikeUtils.likeCondition("title"));
    }
}

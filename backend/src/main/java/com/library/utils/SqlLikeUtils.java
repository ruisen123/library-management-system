package com.library.utils;

/**
 * SQL LIKE 模糊匹配关键字转义（2026-10-24 审计修复）
 *
 * 背景：MyBatis-Plus 的 like() 会把关键字原样拼进 "%关键字%"，未转义时用户输入的
 * <code>%</code> 与 <code>_</code> 会被当成通配符——检索 "%" 命中全库、检索 "a_b"
 * 匹配任意单字符，与"按字面量模糊匹配"的语义不符。
 *
 * 用法：{@code wrapper.apply(SqlLikeUtils.likeCondition("title"), SqlLikeUtils.likePattern(keyword))}
 * 说明：条件片段显式声明 <code>ESCAPE '!'</code>，不依赖数据库默认转义符
 * （MySQL 默认反斜杠，且受 NO_BACKSLASH_ESCAPES 影响，隐式依赖会静默失效）；
 * 参数走 MyBatis-Plus 的 {0} 占位符 => JDBC 预编译参数，不做字符串拼接。
 */
public final class SqlLikeUtils {

    /** LIKE 转义符：取 '!' 而非反斜杠，避免与 MySQL 字符串字面量的反斜杠转义规则混淆 */
    public static final String ESCAPE = "!";

    private SqlLikeUtils() {
    }

    /** 转义关键字中的转义符与通配符（% 与 _），使其按字面量参与 LIKE 匹配 */
    public static String escape(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return keyword;
        }
        return keyword.replace(ESCAPE, ESCAPE + ESCAPE)
                .replace("%", ESCAPE + "%")
                .replace("_", ESCAPE + "_");
    }

    /**
     * 转义并包上两侧通配符，得到 "%关键字%" 形态的 LIKE 参数。
     * 传入 null 或空串时原样返回（调用方一律先做 {@code StrUtil.isNotBlank} 判断），
     * 不做通配拼接——否则会得到 "%null%" 或 "%%"（后者等于全表命中）。
     */
    public static String likePattern(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return keyword;
        }
        return "%" + escape(keyword) + "%";
    }

    /**
     * 生成 "列名 LIKE {0} ESCAPE '!'" 的条件片段，与 {@link #likePattern(String)} 配套使用。
     * 列名必须是数据库列名（如 title / real_name / detail），与实体字段 camelCase 对应。
     */
    public static String likeCondition(String column) {
        return column + " LIKE {0} ESCAPE '" + ESCAPE + "'";
    }
}

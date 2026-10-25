package com.library.utils;

import com.library.common.LoginUser;

/**
 * 用户上下文：基于 ThreadLocal 保存当前请求的登录用户
 * 登录拦截器在 preHandle 中写入，请求结束后由 afterCompletion 清除，避免内存泄漏
 */
public class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    /** 获取当前用户 ID（未登录返回 null） */
    public static Long getUserId() {
        LoginUser user = HOLDER.get();
        return user == null ? null : user.getId();
    }

    /** 获取当前用户角色 */
    public static String getRole() {
        LoginUser user = HOLDER.get();
        return user == null ? null : user.getRole();
    }

    public static void clear() {
        HOLDER.remove();
    }
}

package com.library.annotation;

import java.lang.annotation.*;

/**
 * 角色权限注解：标注在 Controller 方法（或类）上，声明允许访问的角色
 * 用法：@RequireRole({RoleConstant.ADMIN})
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireRole {
    /** 允许访问的角色列表 */
    String[] value();
}

package com.library.interceptor;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.library.annotation.RequireRole;
import com.library.common.LoginUser;
import com.library.common.Result;
import com.library.common.ResultCode;
import com.library.entity.SysUser;
import com.library.mapper.SysUserMapper;
import com.library.utils.JwtUtils;
import com.library.utils.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

/**
 * 登录认证拦截器
 * 1. 从请求头解析 token，校验登录状态，将用户信息写入 ThreadLocal
 * 2. 校验方法上的 @RequireRole 注解，实现接口级角色权限控制
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtils jwtUtils;
    private final SysUserMapper sysUserMapper;

    /** 令牌请求头名称（与配置 jwt.header 一致） */
    @Value("${jwt.header}")
    private String header;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 放行非控制器方法（静态资源等）
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        String token = request.getHeader(header);
        if (token == null || token.isEmpty()) {
            writeJson(response, ResultCode.UNAUTHORIZED);
            return false;
        }

        // 解析令牌
        Long userId = jwtUtils.getUserId(token);
        if (userId == null) {
            writeJson(response, ResultCode.UNAUTHORIZED);
            return false;
        }

        // 从数据库获取最新用户信息（保证角色/状态实时，避免令牌与库不一致）
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || user.getDeleted() != null && user.getDeleted() == 1) {
            writeJson(response, ResultCode.UNAUTHORIZED);
            return false;
        }
        // 冻结账号校验：冻结后即使令牌未过期也不允许访问（登录时已拦截，此处兜底）
        if (user.getStatus() != null && user.getStatus() == 0) {
            writeJson(response, ResultCode.UNAUTHORIZED.getCode(), "账号已冻结，请联系管理员");
            return false;
        }
        // 密码指纹校验（R19-6）：令牌携带签发时的密码指纹，与当前密码哈希比对——
        // 改密/重置密码后指纹不匹配，旧令牌立即失效（机制化覆盖所有改密路径，无需逐处埋点）
        String pfp = jwtUtils.getPwdFingerprint(token);
        if (pfp == null || !pfp.equals(DigestUtil.sha256Hex(user.getPassword()))) {
            writeJson(response, ResultCode.UNAUTHORIZED.getCode(), "登录已过期，请重新登录");
            return false;
        }
        // 角色权限校验
        RequireRole requireRole = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (requireRole == null) {
            requireRole = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
        }
        // 默认拒绝：没有声明 @RequireRole 的接口不再对"所有已登录角色"放行。
        // 登录页/个人中心/公告读取等公共接口必须显式标注允许的角色，新增接口漏标注会直接 403，
        // 避免默认放行把新管理接口暴露给读者。
        if (requireRole == null) {
            writeJson(response, ResultCode.FORBIDDEN);
            return false;
        }
        if (!Arrays.asList(requireRole.value()).contains(user.getRole())) {
            writeJson(response, ResultCode.FORBIDDEN);
            return false;
        }

        // 校验全部通过后再写入上下文：本拦截器返回 false 时 Spring 不会回调 afterCompletion，
        // 若提前 set 会在线程复用下残留上一请求的用户信息（本拦截器为唯一拦截器，interceptorIndex 不前移）
        UserContext.set(new LoginUser(user.getId(), user.getUsername(), user.getRealName(), user.getRole()));

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 清理 ThreadLocal，防止线程复用导致数据串号
        UserContext.clear();
    }

    /** 直接写出 JSON 响应（拦截器阶段异常不经过全局异常处理器） */
    private void writeJson(HttpServletResponse response, ResultCode resultCode) throws Exception {
        writeJson(response, resultCode.getCode(), resultCode.getMsg());
    }

    /** 写出自定义 code/msg 的 JSON 响应 */
    private void writeJson(HttpServletResponse response, Integer code, String msg) throws Exception {
        response.setStatus(200);
        response.setContentType("application/json;charset=UTF-8");
        Result<Void> result = Result.fail(code, msg);
        response.getWriter().write(JSONUtil.toJsonStr(result));
    }
}

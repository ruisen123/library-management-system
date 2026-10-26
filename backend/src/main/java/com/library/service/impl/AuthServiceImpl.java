package com.library.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.library.common.constant.FineConstant;
import com.library.common.constant.NotificationConstant;
import com.library.common.constant.RoleConstant;
import com.library.common.exception.BusinessException;
import com.library.dto.LoginDTO;
import com.library.dto.RegisterDTO;
import com.library.entity.SysUser;
import com.library.event.NotificationEvent;
import com.library.mapper.SysUserMapper;
import com.library.service.AuthService;
import com.library.service.OperationLogService;
import com.library.utils.IpUtils;
import com.library.utils.JwtUtils;
import com.library.utils.RegistrationRateLimiter;
import com.library.vo.LoginVO;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证服务实现
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    /**
     * 登录防爆破：登录名 → [失败次数, 最近失败时间戳(ms), 锁定截止时间戳(ms)]，10 分钟窗口内失败 5 次后，自第 5 次失败起锁定 10 分钟。
     * 说明：计数保存在进程内存（ConcurrentHashMap），随进程重启清零、多实例部署时各实例独立计数；
     *      本项目为单实例部署（见 deploy/部署说明.md），该实现满足需求，无需引入 Redis 等外部存储。
     *      ConcurrentHashMap.compute 保证同一登录名的计数原子，避免并发下计数丢失。
     */
    private static final Map<String, long[]> LOGIN_FAILS = new ConcurrentHashMap<>();
    private static final int MAX_FAILS = 5;
    private static final long WINDOW_MS = 10 * 60 * 1000L;
    /** 不存在用户时也做一次等成本 BCrypt，避免响应时间泄露登录名是否存在 */
    private static final String DUMMY_PASSWORD_HASH = BCrypt.hashpw("libms-dummy-password");

    private final SysUserMapper sysUserMapper;
    private final JwtUtils jwtUtils;
    private final OperationLogService operationLogService;
    private final RegistrationRateLimiter registrationRateLimiter;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public LoginVO login(LoginDTO dto) {
        // 先验密码、再判锁定：锁定期内"密码正确"仍允许登录。
        // 旧实现先判锁定再验密码，任何人用错误口令连打 5 次即可把管理员锁在门外（可用性攻击）；
        // 锁定只用于拦截继续猜密码的请求，不应成为拒绝合法用户的开关。
        // 计数键必须与数据库的账号匹配口径一致：DEFAULT COLLATION utf8mb4_general_ci 忽略大小写与
        // 尾随空格，若按原始输入分桶，攻击者可轮换 Admin/ADMIN/"admin " 绕过 5 次锁定。
        String loginName = dto.getUsername() == null ? "" : dto.getUsername().trim();
        String loginKey = loginName.toLowerCase(Locale.ROOT);
        long[] fails = LOGIN_FAILS.get(loginKey);
        boolean locked = fails != null && fails[2] > System.currentTimeMillis();
        // 按登录名查询用户
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, loginName)
        );
        // 密码校验：数据库存的是 BCrypt 密文，绝不明文比对。
        // 用户不存在时也执行一次等成本校验，避免通过响应时间枚举登录名。
        boolean passwordOk;
        if (user == null) {
            BCrypt.checkpw(dto.getPassword(), DUMMY_PASSWORD_HASH);
            passwordOk = false;
        } else {
            passwordOk = BCrypt.checkpw(dto.getPassword(), user.getPassword());
        }
        if (!passwordOk) {
            // 锁定期内的错误尝试：直接拒绝并留痕（密码正确者不会走到这里）
            if (locked) {
                operationLogService.recordBestEffort(user == null ? null : user.getId(), "LOGIN_LOCKED",
                        String.format("登录被拒：账号锁定中[%s]", dto.getUsername()));
                throw new BusinessException("登录失败次数过多，账号已临时锁定，请 10 分钟后再试");
            }
            // 失败计数（滑动窗口：超窗则重置后重新计数）
            LOGIN_FAILS.compute(loginKey, (k, v) -> {
                long now = System.currentTimeMillis();
                long[] cur = (v == null || now - v[1] > WINDOW_MS) ? new long[]{0, now, 0} : v;
                cur[0]++;
                if (cur[0] >= MAX_FAILS) {
                    // 从第 5 次失败起再锁 10 分钟；不再用首次失败时间充当锁到期时间
                    cur[2] = now + WINDOW_MS;
                }
                return cur;
            });
            // 容量兜底：只清除已过期条目，绝不清空正在锁定的账号（避免灌入大量用户名绕过锁定）。
            // 锁到期时间字段为 0 表示未锁定；超过 1 万条时仅清掉未锁定且已过期条目。
            if (LOGIN_FAILS.size() > 10000) {
                long now = System.currentTimeMillis();
                LOGIN_FAILS.entrySet().removeIf(e -> e.getValue()[2] <= now);
            }
            // 审计留痕：失败登录此前完全无痕，暴力破解不可观测（user_id 置 null：登录名可能不存在）
            // 注意：登录名已被 @Size(max=30) 收敛，detail 长度有界
            operationLogService.recordBestEffort(null, "LOGIN_FAIL",
                    String.format("登录失败：登录名[%s]", dto.getUsername()));
            throw new BusinessException("登录名或密码错误");
        }
        // 密码正确：清除失败记录（锁定状态随之解除）
        LOGIN_FAILS.remove(loginKey);
        // 账号状态校验：冻结账号的登录尝试同样留痕——"账号被冻结后仍被持续尝试登录"是重要风险信号，
        // 此前该分支在凭据校验通过后直接抛出、未写审计，导致该场景在审计中完全不可见
        if (user.getStatus() != null && user.getStatus() == 0) {
            operationLogService.recordBestEffort(user.getId(), "LOGIN_FAIL",
                    String.format("登录失败：账号已冻结[%s]", user.getUsername()));
            throw new BusinessException("账号已冻结，请联系管理员");
        }
        // 签发令牌（附带密码指纹：R19-6 改密/重置后旧令牌自动失效）
        String token = jwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole(),
                DigestUtil.sha256Hex(user.getPassword()));

        // 登录留痕（关键操作日志）
        operationLogService.recordBestEffort(user.getId(), "LOGIN",
                String.format("用户[%s]登录成功", user.getUsername()));

        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setUser(user);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginVO register(RegisterDTO dto) {
        // 公开写入口先限流：按来源 IP + 全局窗口双重约束，避免脚本批量灌号
        registrationRateLimiter.checkAndRecord(IpUtils.getClientIp());

        String username = dto.getUsername();
        Long usernameCount = sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (usernameCount != null && usernameCount > 0) {
            throw new BusinessException("登录名已存在，请更换");
        }
        // 手机号作为读者联系与找回依据，公开注册时要求全局唯一（逻辑删除后可复用）
        String phone = dto.getPhone();
        Long phoneCount = sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getPhone, phone));
        if (phoneCount != null && phoneCount > 0) {
            throw new BusinessException("该手机号已注册，请直接登录");
        }

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(BCrypt.hashpw(dto.getPassword()));
        user.setRealName(StrUtil.trim(dto.getRealName()));
        // 角色固定 READER：公开接口不接收 role，杜绝自助注册提权
        user.setRole(RoleConstant.READER);
        // 证号由系统分配：插入拿到自增 id 后再回填，避免随机数碰撞与用户自选证号
        user.setReaderNo(null);
        user.setGender(dto.getGender() == null ? 0 : dto.getGender());
        user.setPhone(phone);
        user.setStatus(1);
        user.setBorrowedCount(0);
        user.setBorrowLimit(FineConstant.MAX_BORROW);
        user.setMaxUnpaid(BigDecimal.ZERO);
        user.setValidUntil(null);
        user.setEmail(StrUtil.isBlank(dto.getEmail()) ? null : StrUtil.trim(dto.getEmail()));
        user.setEmailNotify(1);
        sysUserMapper.insert(user);

        String readerNo = "R" + LocalDate.now().getYear() + String.format("%06d", user.getId());
        int rows = sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, user.getId())
                .set(SysUser::getReaderNo, readerNo));
        if (rows == 0) {
            throw new BusinessException("注册失败，请稍后重试");
        }
        user.setReaderNo(readerNo);

        // 注册是业务写入：审计与账号同事务，日志失败则注册回滚
        operationLogService.record(user.getId(), "REGISTER",
                String.format("读者自助注册：%s（证号 %s）", user.getUsername(), readerNo));

        // 欢迎通知（AFTER_COMMIT 落库，不影响注册主流程）
        eventPublisher.publishEvent(new NotificationEvent(user.getId(),
                NotificationConstant.TYPE_WELCOME, "欢迎使用 LibMS",
                "读者证号 " + readerNo + " 已开通，默认最多可借 " + FineConstant.MAX_BORROW
                        + " 册、借期 " + FineConstant.BORROW_DAYS + " 天，可在个人中心维护通知邮箱。",
                NotificationConstant.BIZ_USER, user.getId(), "WELCOME:" + user.getId()));

        // 注册即登录：返回与登录一致的令牌结构，前端无需再发一次登录请求
        String token = jwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole(),
                DigestUtil.sha256Hex(user.getPassword()));
        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setUser(user);
        return vo;
    }
}

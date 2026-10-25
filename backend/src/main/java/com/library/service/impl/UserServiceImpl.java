package com.library.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.library.common.ResultCode;
import com.library.common.constant.BorrowStatusConstant;
import com.library.common.constant.FineConstant;
import com.library.common.exception.BusinessException;
import com.library.common.constant.RoleConstant;
import com.library.dto.PasswordDTO;
import com.library.dto.ProfileDTO;
import com.library.dto.UserDTO;
import com.library.entity.BorrowRecord;
import com.library.entity.FineRecord;
import com.library.entity.SysUser;
import com.library.mapper.BorrowRecordMapper;
import com.library.mapper.FineRecordMapper;
import com.library.mapper.SysUserMapper;
import com.library.service.OperationLogService;
import com.library.service.ReservationService;
import com.library.service.UserService;
import com.library.utils.SqlLikeUtils;
import com.library.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 用户管理服务实现
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final SysUserMapper sysUserMapper;
    private final BorrowRecordMapper borrowRecordMapper;
    private final FineRecordMapper fineRecordMapper;
    private final OperationLogService operationLogService;
    private final ReservationService reservationService;

    @Override
    public IPage<SysUser> page(int page, int size, String role, String keyword, Integer status) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        // 角色过滤
        if (StrUtil.isNotBlank(role)) {
            wrapper.eq(SysUser::getRole, role);
        }
        // 关键词：姓名 / 证号 / 登录名 模糊匹配；% 与 _ 按字面量匹配
        if (StrUtil.isNotBlank(keyword)) {
            String like = SqlLikeUtils.likePattern(keyword);
            wrapper.and(w -> w.apply(SqlLikeUtils.likeCondition("real_name"), like)
                    .or().apply(SqlLikeUtils.likeCondition("reader_no"), like)
                    .or().apply(SqlLikeUtils.likeCondition("username"), like));
        }
        // 状态过滤
        if (status != null) {
            wrapper.eq(SysUser::getStatus, status);
        }
        wrapper.orderByDesc(SysUser::getCreateTime);
        return sysUserMapper.selectPage(new Page<>(page, size), wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(UserDTO dto) {
        // 登录名非空校验（新增必填）
        if (StrUtil.isBlank(dto.getUsername())) {
            throw new BusinessException("登录名不能为空");
        }
        // 角色合法性校验（防御非法值写入）
        if (!RoleConstant.ADMIN.equals(dto.getRole())
                && !RoleConstant.LIBRARIAN.equals(dto.getRole())
                && !RoleConstant.READER.equals(dto.getRole())) {
            throw new BusinessException("角色不合法");
        }
        // 登录名唯一性校验
        Long usernameCount = sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, dto.getUsername())
        );
        if (usernameCount > 0) {
            throw new BusinessException("登录名已存在");
        }
        // 手机号唯一性校验（与自助注册同口径；逻辑删除后可复用）
        if (StrUtil.isNotBlank(dto.getPhone())) {
            Long phoneCount = sysUserMapper.selectCount(
                    new LambdaQueryWrapper<SysUser>().eq(SysUser::getPhone, dto.getPhone()));
            if (phoneCount > 0) {
                throw new BusinessException("该手机号已存在");
            }
        }
        // 读者证号唯一性校验
        if (StrUtil.isNotBlank(dto.getReaderNo())) {
            Long noCount = sysUserMapper.selectCount(
                    new LambdaQueryWrapper<SysUser>().eq(SysUser::getReaderNo, dto.getReaderNo())
            );
            if (noCount > 0) {
                throw new BusinessException("读者证号已存在");
            }
        }
        SysUser user = new SysUser();
        user.setUsername(dto.getUsername());
        // 密码加密存储：默认 123456
        user.setPassword(BCrypt.hashpw(StrUtil.blankToDefault(dto.getPassword(), "123456")));
        user.setRealName(dto.getRealName());
        user.setRole(dto.getRole());
        // 空证号归一为 null：reader_no 的唯一索引基于生成列 IF(deleted=0, reader_no, NULL)，
        // 空串不是 NULL、会占用唯一位，导致后续"未填证号"的账号全部撞唯一约束
        user.setReaderNo(StrUtil.isBlank(dto.getReaderNo()) ? null : dto.getReaderNo());
        user.setGender(dto.getGender() == null ? 0 : dto.getGender());
        user.setPhone(dto.getPhone());
        user.setStatus(1);
        user.setBorrowedCount(0);
        user.setBorrowLimit(dto.getBorrowLimit() == null ? FineConstant.MAX_BORROW : dto.getBorrowLimit());
        user.setMaxUnpaid(dto.getMaxUnpaid() == null ? BigDecimal.ZERO : dto.getMaxUnpaid());
        user.setValidUntil(dto.getValidUntil());
        user.setEmail(StrUtil.isBlank(dto.getEmail()) ? null : StrUtil.trim(dto.getEmail()));
        user.setEmailNotify(dto.getEmailNotify() == null ? 1 : dto.getEmailNotify());
        sysUserMapper.insert(user);
        // 管理侧写操作审计留痕
        operationLogService.record("USER_ADD", "新增用户[" + dto.getUsername() + "]（" + dto.getRole() + "）");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(UserDTO dto) {
        // 先锁用户行，再做"在借/角色"守卫：与 delete() 同口径，
        // 关闭"守卫读到无在借 → 并发借书提交 → 改角色得手"的 check-then-act 窗口。
        sysUserMapper.lockByIdForUpdate(dto.getId());
        // 存在性校验（当前读：锁后读最新已提交角色）
        SysUser exist = sysUserMapper.selectByIdForUpdate(dto.getId());
        if (exist == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        // 角色合法性校验（与新增口径一致，防止绕过前端写入非法角色）
        if (!RoleConstant.ADMIN.equals(dto.getRole())
                && !RoleConstant.LIBRARIAN.equals(dto.getRole())
                && !RoleConstant.READER.equals(dto.getRole())) {
            throw new BusinessException("角色不合法");
        }
        // 护栏：将当前唯一的一名系统管理员改出 ADMIN 角色，会导致系统失去管理员
        if (RoleConstant.ADMIN.equals(exist.getRole()) && !RoleConstant.ADMIN.equals(dto.getRole())) {
            Long adminCount = sysUserMapper.selectCount(
                    new LambdaQueryWrapper<SysUser>().eq(SysUser::getRole, RoleConstant.ADMIN));
            if (adminCount != null && adminCount <= 1) {
                throw new BusinessException("系统至少需要一名系统管理员");
            }
        }
        // 护栏：存在在借图书的读者不得调整角色（改出 READER 后将失去“我的借阅”自助入口，且 borrowed_count/reader_no 残留）
        if (RoleConstant.READER.equals(exist.getRole()) && !RoleConstant.READER.equals(dto.getRole())) {
            Long borrowing = borrowRecordMapper.selectCount(new LambdaQueryWrapper<BorrowRecord>()
                    .eq(BorrowRecord::getUserId, dto.getId())
                    .eq(BorrowRecord::getStatus, BorrowStatusConstant.BORROWING));
            if (borrowing != null && borrowing > 0) {
                throw new BusinessException("该读者存在在借图书，请先归还后再调整角色");
            }
            // 角色改出读者后不再有读者端入口，未处理的预约需一并取消并顺延队首
            reservationService.cancelActiveByUser(dto.getId());
        }
        // 护栏：读者证号唯一性校验（与新增时口径一致，逻辑删除后可复用）
        if (StrUtil.isNotBlank(dto.getReaderNo())) {
            Long noCount = sysUserMapper.selectCount(
                    new LambdaQueryWrapper<SysUser>()
                            .eq(SysUser::getReaderNo, dto.getReaderNo())
                            .ne(SysUser::getId, dto.getId()));
            if (noCount > 0) {
                throw new BusinessException("读者证号已存在");
            }
        }
        // 手机号唯一性校验（排除自身；逻辑删除后可复用）
        if (StrUtil.isNotBlank(dto.getPhone())) {
            Long phoneCount = sysUserMapper.selectCount(
                    new LambdaQueryWrapper<SysUser>()
                            .eq(SysUser::getPhone, dto.getPhone())
                            .ne(SysUser::getId, dto.getId()));
            if (phoneCount > 0) {
                throw new BusinessException("该手机号已存在");
            }
        }
        // 仅更新可编辑字段，登录名与密码不在此修改
        LambdaUpdateWrapper<SysUser> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(SysUser::getId, dto.getId())
                .set(SysUser::getRealName, dto.getRealName())
                .set(SysUser::getRole, dto.getRole())
                .set(SysUser::getReaderNo, StrUtil.isBlank(dto.getReaderNo()) ? null : dto.getReaderNo())
                // gender 列 NOT NULL：空值兜底为 0（与 add()/updateProfile() 口径一致，避免 NULL 触发完整性约束异常）
                .set(SysUser::getGender, dto.getGender() == null ? 0 : dto.getGender())
                .set(SysUser::getPhone, dto.getPhone())
                .set(SysUser::getBorrowLimit, dto.getBorrowLimit() == null ? FineConstant.MAX_BORROW : dto.getBorrowLimit())
                .set(SysUser::getMaxUnpaid, dto.getMaxUnpaid() == null ? BigDecimal.ZERO : dto.getMaxUnpaid())
                .set(SysUser::getValidUntil, dto.getValidUntil())
                .set(SysUser::getEmail, StrUtil.isBlank(dto.getEmail()) ? null : StrUtil.trim(dto.getEmail()))
                .set(SysUser::getEmailNotify, dto.getEmailNotify() == null ? 1 : dto.getEmailNotify());
        sysUserMapper.update(null, wrapper);
        operationLogService.record("USER_UPDATE", "编辑用户ID " + dto.getId() + "（角色→" + dto.getRole()
                + "，可借上限→" + (dto.getBorrowLimit() == null ? FineConstant.MAX_BORROW : dto.getBorrowLimit())
                + "，最大欠费→" + (dto.getMaxUnpaid() == null ? "0.00" : dto.getMaxUnpaid().toPlainString()) + "）");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void toggleStatus(Long id) {
        // 护栏：禁止冻结当前登录账号——冻结后该账号令牌立即失效且无人能解冻（与 delete 的自伤防护口径一致）
        if (UserContext.getUserId() != null && UserContext.getUserId().equals(id)) {
            throw new BusinessException("不能冻结当前登录账号");
        }
        // 先读取原状态：条件更新是"翻转"语义，留痕需要知道翻转后的结果态（冻结/启用）与目标账号，
        // 否则日志只能记录"切换了状态"，无法回答到底冻结还是启用
        SysUser exist = sysUserMapper.selectById(id);
        if (exist == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        // 条件更新：只翻转 status，禁止整行回写，避免覆盖并发借书更新的 borrowed_count 等字段
        int rows = sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, id)
                .setSql("status = 1 - status"));
        if (rows == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        // 冻结后无法借书，保留"待取"预约会白占队首 7 天：立即取消并顺延
        if (Integer.valueOf(1).equals(exist.getStatus())) {
            reservationService.cancelActiveByUser(id);
        }
        operationLogService.record("USER_STATUS",
                (Integer.valueOf(1).equals(exist.getStatus()) ? "冻结" : "启用")
                        + "用户[" + exist.getUsername() + "]");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(Long id) {
        LambdaUpdateWrapper<SysUser> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(SysUser::getId, id).set(SysUser::getPassword, BCrypt.hashpw("123456"));
        // 影响行数为 0 说明用户不存在或已被删除，避免对无效 id 静默“成功”
        int rows = sysUserMapper.update(null, wrapper);
        if (rows == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        // 高权限操作审计留痕（此前无痕）
        operationLogService.record("RESET_PASSWORD", "重置用户ID " + id + " 的密码为初始密码");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        // 防止误删自己：删除后当前令牌对应的账号立即失效，且系统可能失去最后一个管理员
        if (UserContext.getUserId() != null && UserContext.getUserId().equals(id)) {
            throw new BusinessException("不能删除当前登录账号");
        }
        // 先对用户行加锁再执行护栏读取：借书事务的 UPDATE sys_user（borrowed_count+1）持有同一行 X 锁，
        // 二者互斥。否则并发借书可在"查到无在借"与"逻辑删除"之间提交，删除得手后仍存在在借记录，
        // 还书时 borrowed_count 的更新会被 deleted=0 过滤掉，计数永久漂移（巡检 R2 事后才报）。
        sysUserMapper.lockByIdForUpdate(id);
        // 业务护栏（docs/04 §五.4）：存在在借记录或未缴罚金的用户禁止删除，
        // 否则其在借图书无法再经页面归还、库存与罚金将长期悬挂
        Long borrowing = borrowRecordMapper.selectCount(new LambdaQueryWrapper<BorrowRecord>()
                .eq(BorrowRecord::getUserId, id)
                .eq(BorrowRecord::getStatus, BorrowStatusConstant.BORROWING));
        if (borrowing != null && borrowing > 0) {
            throw new BusinessException("该用户存在在借图书，禁止删除，请先归还");
        }
        Long unpaidFine = fineRecordMapper.selectCount(new LambdaQueryWrapper<FineRecord>()
                .eq(FineRecord::getUserId, id)
                .eq(FineRecord::getStatus, FineConstant.STATUS_UNPAID));
        if (unpaidFine != null && unpaidFine > 0) {
            throw new BusinessException("该用户存在未缴罚金，禁止删除，请先缴清");
        }
        // 删除读者时其预约已无归属：先取消有效预约（待取的顺延队首），再逻辑删除
        reservationService.cancelActiveByUser(id);
        // 逻辑删除（MP @TableLogic 自动转为 update deleted=1），影响行数为 0 说明用户不存在
        int rows = sysUserMapper.deleteById(id);
        if (rows == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        operationLogService.record("USER_DELETE", "删除用户ID " + id);
    }

    @Override
    public List<SysUser> listReaders(String keyword) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUser::getRole, RoleConstant.READER);
        if (StrUtil.isNotBlank(keyword)) {
            // 关键字按字面量匹配（%/_ 已转义，显式 ESCAPE，2026-10-24 审计修复）；
            // 列名 = sys_user.real_name / sys_user.reader_no（docs/04_数据库设计.sql:44,46）
            String like = SqlLikeUtils.likePattern(keyword);
            wrapper.and(w -> w.apply(SqlLikeUtils.likeCondition("real_name"), like)
                    .or().apply(SqlLikeUtils.likeCondition("reader_no"), like));
        }
        wrapper.orderByDesc(SysUser::getId);
        // 读者检索固定上限：有关键词 20 条、无关键词 100 条，防止全量返回
        wrapper.last(StrUtil.isNotBlank(keyword) ? "LIMIT 20" : "LIMIT 100");
        return sysUserMapper.selectList(wrapper);
    }

    @Override
    public SysUser profile() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException("未登录");
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        return user;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateProfile(ProfileDTO dto) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException("未登录");
        }
        String phone = StrUtil.isBlank(dto.getPhone()) ? null : dto.getPhone().trim();
        if (phone != null) {
            Long phoneCount = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getPhone, phone)
                    .ne(SysUser::getId, userId));
            if (phoneCount != null && phoneCount > 0) {
                throw new BusinessException("该手机号已存在");
            }
        }
        int rows = sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .set(SysUser::getRealName, dto.getRealName())
                .set(SysUser::getGender, dto.getGender() == null ? 0 : dto.getGender())
                .set(SysUser::getPhone, phone)
                .set(SysUser::getEmail, StrUtil.isBlank(dto.getEmail()) ? null : StrUtil.trim(dto.getEmail()))
                .set(SysUser::getEmailNotify, dto.getEmailNotify() == null ? 1 : dto.getEmailNotify()));
        if (rows == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        operationLogService.record("UPDATE_PROFILE", "修改个人资料（用户ID " + userId + "）");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePassword(PasswordDTO dto) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException("未登录");
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        // 原密码校验通过才允许修改
        if (!BCrypt.checkpw(dto.getOldPassword(), user.getPassword())) {
            throw new BusinessException("原密码错误");
        }
        int rows = sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .set(SysUser::getPassword, BCrypt.hashpw(dto.getNewPassword())));
        if (rows == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        // 敏感操作审计留痕
        operationLogService.record("UPDATE_PASSWORD", "修改登录密码（用户ID " + userId + "）");
    }
}

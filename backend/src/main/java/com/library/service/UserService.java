package com.library.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.library.dto.PasswordDTO;
import com.library.dto.ProfileDTO;
import com.library.dto.UserDTO;
import com.library.entity.SysUser;

import java.util.List;

/**
 * 用户管理服务接口
 */
public interface UserService {

    /**
     * 分页查询用户
     * @param page    页码
     * @param size    每页条数
     * @param role    角色（可选）
     * @param keyword 关键词（姓名/证号/登录名，可选）
     * @param status  状态（可选）
     */
    IPage<SysUser> page(int page, int size, String role, String keyword, Integer status);

    /** 新增用户 */
    void add(UserDTO dto);

    /** 编辑用户 */
    void update(UserDTO dto);

    /** 启用/冻结切换 */
    void toggleStatus(Long id);

    /** 重置密码为 123456 */
    void resetPassword(Long id);

    /** 删除用户（逻辑删除） */
    void delete(Long id);

    /** 借书办理-读者检索（证号/姓名模糊查询，图书管理员使用） */
    List<SysUser> listReaders(String keyword);

    /** 个人中心：查询当前登录用户最新信息 */
    SysUser profile();

    /** 个人中心：修改资料（姓名/性别/电话） */
    void updateProfile(ProfileDTO dto);

    /** 个人中心：修改密码（校验原密码） */
    void updatePassword(PasswordDTO dto);
}

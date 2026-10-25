package com.library.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.library.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * SysUser 表 Mapper：继承 BaseMapper 获得通用 CRUD 能力，复杂查询在此扩展
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 按 id 批量查询用户展示信息（自定义 SQL 不附加 @TableLogic 的 deleted=0 过滤，
     * 用于借阅/罚金历史记录回显：用户被逻辑删除后历史记录仍能显示姓名与证号）
     */
    @Select("<script>SELECT id, real_name AS realName, reader_no AS readerNo FROM sys_user WHERE id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<Map<String, Object>> selectUsersIgnoreDeleted(@Param("ids") List<Long> ids);

    /**
     * 按主键对用户行加锁（FOR UPDATE，忽略逻辑删除，已删行同样可锁）。
     * 供 delete() 的"在借/欠费禁删"守卫与删除动作在同一事务内串行化：
     * 借书事务的 UPDATE sys_user（borrowed_count+1）持有同一行 X 锁，二者互斥，
     * 从而关闭"守卫读取（未见在借）→ 并发借书提交 → 删除得手"的 check-then-act 时序窗。
     */
    @Select("SELECT id FROM sys_user WHERE id = #{id} FOR UPDATE")
    Long lockByIdForUpdate(@Param("id") Long id);

    /** 加锁后的当前读：用于"改角色"等守卫，避免 RR 快照读到加锁前的旧角色 */
    @Select("SELECT * FROM sys_user WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    SysUser selectByIdForUpdate(@Param("id") Long id);

    /**
     * 按姓名/证号关键词查用户 id（自定义 SQL 不附加 @TableLogic 的 deleted=0 过滤）。
     * 供借阅/罚金历史检索使用：读者被逻辑删除后，用其旧证号仍应能搜到历史流水
     * （此前走 MP 默认查询会过滤已删用户，导致 keyword 检索结果为空）。
     */
    @Select("SELECT id FROM sys_user WHERE real_name LIKE #{keyword} ESCAPE '!' "
            + "OR reader_no LIKE #{keyword} ESCAPE '!'")
    List<Long> selectIdsByKeywordIgnoreDeleted(@Param("keyword") String keyword);
}

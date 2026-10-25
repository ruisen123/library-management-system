package com.library.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.library.entity.Book;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * Book 表 Mapper：继承 BaseMapper 获得通用 CRUD 能力，复杂查询在此扩展
 */
@Mapper
public interface BookMapper extends BaseMapper<Book> {

    /**
     * 按 id 批量查询书名（自定义 SQL 不附加 @TableLogic 的 deleted=0 过滤，
     * 用于借阅/罚金历史记录回显：图书被逻辑删除后仍能显示其书名）
     */
    @Select("<script>SELECT id, title FROM book WHERE id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<Map<String, Object>> selectTitlesByIds(@Param("ids") List<Long> ids);

    /**
     * 按主键对图书行加锁（FOR UPDATE，忽略逻辑删除，已删行同样可锁）。
     * 供 delete() 的"在借禁删"守卫与删除动作在同一事务内串行化：
     * 借书事务的 UPDATE book（扣库存）持有同一行 X 锁，二者互斥，
     * 从而关闭"守卫读取（未见在借）→ 并发借书提交 → 删除得手"的 check-then-act 时序窗。
     */
    @Select("SELECT id FROM book WHERE id = #{id} FOR UPDATE")
    Long lockByIdForUpdate(@Param("id") Long id);

    /**
     * 按主键当前读整行（FOR UPDATE，已删行不返回）。
     * 预约/借书在持有图书行锁后必须用它读取库存：
     * 普通 selectById 是 RR 快照读，事务早于加锁建立的快照可能看到已过期的库存，
     * 导致"库存已借空仍判定有副本"的超发。
     */
    @Select("SELECT * FROM book WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    Book selectByIdForUpdate(@Param("id") Long id);

    /**
     * 按书名关键词查图书 id（自定义 SQL 不附加 @TableLogic 的 deleted=0 过滤）。
     * 供借阅/罚金历史检索使用：图书被逻辑删除后，用其旧书名仍应能搜到历史流水。
     */
    @Select("SELECT id FROM book WHERE title LIKE #{keyword} ESCAPE '!'")
    List<Long> selectIdsByKeywordIgnoreDeleted(@Param("keyword") String keyword);
}

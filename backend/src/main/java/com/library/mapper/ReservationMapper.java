package com.library.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.library.entity.Reservation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * Reservation 表 Mapper
 */
@Mapper
public interface ReservationMapper extends BaseMapper<Reservation> {

    /**
     * 该书已分配过的最大预约序号（含历史记录，避免取消后序号复用造成排队歧义）。
     *
     * 必须 FOR UPDATE（当前读）：调用方虽已用 bookMapper.lockByIdForUpdate 锁住图书行，
     * 但本表查询若走普通快照读，在 REPEATABLE READ 下读到的仍是"事务第一条普通 SELECT 时"的旧快照
     * （reserve() 里 sysUserMapper.selectById 早于加锁执行，快照已建立），
     * 并发预约会算出相同序号 → 撞 uk_reservation_book_queue → 被当成"冲突请重试"。
     * 实测未加锁时 8 并发仅 2 成功。加 FOR UPDATE 后读最新已提交值。
     * 不会引入死锁：同书的并发已被图书行锁串行化，本间隙锁只存在于串行临界区内。
     */
    @Select("SELECT IFNULL(MAX(queue_no), 0) FROM reservation WHERE book_id = #{bookId} FOR UPDATE")
    Integer selectMaxQueueNo(@Param("bookId") Long bookId);

    /**
     * 当前读：锁住该书全部有效预约（排队中 + 待取书），按 queue_no 排序。
     * promoteNext 在 RR 事务中若用普通快照读，可能看不到锁图书行前其他事务已提交的
     * READY 提升，从而把 WAITING 提升到超过 stock。此查询使用 FOR UPDATE 读取最新
     * 已提交状态；同书并发已被图书行锁串行化，锁序仍为 book -> reservation。
     */
    @Select("SELECT * FROM reservation WHERE book_id = #{bookId} AND status IN (1, 2) "
            + "ORDER BY queue_no FOR UPDATE")
    List<Reservation> selectActiveByBookForUpdate(@Param("bookId") Long bookId);

    /**
     * 批量统计各书"待取书"预约数（status=2）。
     * 供图书列表/详情展示"真实可借数"（stock - 待取预约数）：
     * 待取预约不扣库存（保持巡检 R1 口径），所以必须单独统计，否则会出现
     * "显示可借 6 本、实际 6 本都被预约占住、一借就被拒"的显示与事实不一致。
     */
    @Select("<script>SELECT book_id AS bookId, COUNT(*) AS cnt FROM reservation "
            + "WHERE status = 2 AND book_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY book_id</script>")
    List<Map<String, Object>> countReadyByBookIds(@Param("ids") List<Long> ids);

    /** 统计同书前面还有多少位有效预约（排队中 + 待取书） */
    @Select("SELECT COUNT(*) FROM reservation WHERE book_id = #{bookId} "
            + "AND status IN (1, 2) AND queue_no < #{queueNo}")
    Integer countAhead(@Param("bookId") Long bookId, @Param("queueNo") Integer queueNo);
}

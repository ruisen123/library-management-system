package com.library.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 统计分析专用 Mapper：聚合查询集中在 SQL 层完成（GROUP BY / 求和），避免 Java 内存遍历
 */
@Mapper
public interface StatsMapper {

    /**
     * 近 12 个月借阅量趋势（按借出日期 borrow_date 分组，与总览"今日借还"口径一致）。
     * 窗口起点由 Java 传入（与 overview 的 LocalDate.now() 同源）：
     * 原先用 SQL CURDATE() 依赖数据库会话时区，与 JVM 时区不一致（如 DB 部署在 UTC 主机）时会产生一天错位。
     */
    @Select("SELECT DATE_FORMAT(borrow_date, '%Y-%m') AS month, COUNT(*) AS cnt " +
            "FROM borrow_record " +
            "WHERE borrow_date >= #{windowStart} " +
            "GROUP BY month ORDER BY month")
    List<Map<String, Object>> selectBorrowTrend(@Param("windowStart") LocalDate windowStart);

    /**
     * 分类借阅占比：按 borrow_record 行数聚合（与趋势图同一基准）。
     * 旧实现按 book.borrow_count 且过滤已删图书：图书一旦逻辑删除，趋势仍统计其历史借阅、
     * 占比却把这段历史扣掉，两张图必然对不上。借阅流水不随图书软删消失，故以流水为准。
     */
    @Select("SELECT IFNULL(c.name, '未分类') AS name, COUNT(br.id) AS value " +
            "FROM borrow_record br " +
            "LEFT JOIN book b ON b.id = br.book_id " +
            "LEFT JOIN category c ON c.id = b.category_id " +
            "GROUP BY c.id, c.name ORDER BY value DESC")
    List<Map<String, Object>> selectCategoryPie();

    /** 馆藏总册数 */
    @Select("SELECT IFNULL(SUM(total_stock), 0) FROM book WHERE deleted = 0")
    Long sumTotalStock();

    /**
     * 报表导出：借阅明细（按借出日期区间）。
     * 读者/图书用 LEFT JOIN 且不过滤 deleted：历史记录即使实体已逻辑删除，报表仍应可追溯。
     */
    @Select("SELECT br.id AS id, u.reader_no AS readerNo, u.real_name AS readerName, "
            + "b.title AS bookTitle, b.isbn AS isbn, br.borrow_date AS borrowDate, "
            + "br.due_date AS dueDate, br.return_date AS returnDate, br.status AS status, "
            + "br.renew_count AS renewCount "
            + "FROM borrow_record br "
            + "LEFT JOIN sys_user u ON u.id = br.user_id "
            + "LEFT JOIN book b ON b.id = br.book_id "
            + "WHERE br.borrow_date BETWEEN #{start} AND #{end} "
            + "ORDER BY br.id DESC")
    List<Map<String, Object>> selectBorrowDetail(@Param("start") LocalDate start,
                                                 @Param("end") LocalDate end);

    /** 报表导出：罚金明细（按罚金登记日期区间） */
    @Select("SELECT f.id AS id, u.reader_no AS readerNo, u.real_name AS readerName, "
            + "b.title AS bookTitle, f.amount AS amount, f.type AS type, f.status AS status, "
            + "f.paid_time AS paidTime, f.remark AS remark, f.create_time AS createTime "
            + "FROM fine_record f "
            + "LEFT JOIN sys_user u ON u.id = f.user_id "
            + "LEFT JOIN borrow_record br ON br.id = f.borrow_id "
            + "LEFT JOIN book b ON b.id = br.book_id "
            + "WHERE DATE(f.create_time) BETWEEN #{start} AND #{end} "
            + "ORDER BY f.id DESC")
    List<Map<String, Object>> selectFineDetail(@Param("start") LocalDate start,
                                               @Param("end") LocalDate end);
}
package com.library.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.library.entity.BorrowRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

/**
 * BorrowRecord 表 Mapper：继承 BaseMapper 获得通用 CRUD 能力，复杂查询在此扩展
 */
@Mapper
public interface BorrowRecordMapper extends BaseMapper<BorrowRecord> {

    /**
     * 逾期天数计算（SQL 层 DATEDIFF，不在 Java 内存遍历计算）
     * @param id    借阅记录 ID
     * @param today 归还/查询基准日期
     * @return 今日 - 应还日期 的天数（>0 表示逾期）
     */
    @Select("SELECT DATEDIFF(#{today}, due_date) FROM borrow_record WHERE id = #{id}")
    Long selectOverdueDays(@Param("id") Long id, @Param("today") LocalDate today);
}

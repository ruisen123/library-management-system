package com.library.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.library.entity.FineRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

/**
 * FineRecord 表 Mapper：继承 BaseMapper 获得通用 CRUD 能力，复杂查询在此扩展
 */
@Mapper
public interface FineRecordMapper extends BaseMapper<FineRecord> {

    /** 指定读者、指定状态的罚金合计（用于读者最大欠费额度校验） */
    @Select("SELECT COALESCE(SUM(amount), 0) FROM fine_record WHERE user_id = #{userId} AND status = #{status}")
    BigDecimal sumAmountByUserAndStatus(@Param("userId") Long userId, @Param("status") Integer status);
}

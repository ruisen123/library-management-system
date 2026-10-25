package com.library.mapper;

import com.library.audit.AuditSqlProvider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

import java.util.List;

/**
 * 巡检查询 Mapper
 *
 * 说明：此处为自定义 Provider SQL，不经 MyBatis-Plus 的逻辑删除改写，
 * 因此"是否包含已删除数据"由每条规则自行声明，这也是本机制能在删除场景下
 * 正确工作的前提（普通业务查询会被自动过滤 deleted=1，查不到遗留异常）。
 */
@Mapper
public interface AuditMapper {

    /** 统计某规则命中的异常行数 */
    @SelectProvider(type = AuditSqlProvider.class, method = "countSql")
    Long countByRule(@Param("ruleId") String ruleId);

    /** 取某规则的异常定位样本（最多 5 条） */
    @SelectProvider(type = AuditSqlProvider.class, method = "locationSql")
    List<String> locateByRule(@Param("ruleId") String ruleId);
}

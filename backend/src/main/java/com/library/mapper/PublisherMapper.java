package com.library.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.library.entity.Publisher;
import org.apache.ibatis.annotations.Mapper;

/**
 * Publisher 表 Mapper：继承 BaseMapper 获得通用 CRUD 能力，复杂查询在此扩展
 */
@Mapper
public interface PublisherMapper extends BaseMapper<Publisher> {
}

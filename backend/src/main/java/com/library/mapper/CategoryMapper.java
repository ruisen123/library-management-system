package com.library.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.library.entity.Category;
import org.apache.ibatis.annotations.Mapper;

/**
 * Category 表 Mapper：继承 BaseMapper 获得通用 CRUD 能力，复杂查询在此扩展
 */
@Mapper
public interface CategoryMapper extends BaseMapper<Category> {
}

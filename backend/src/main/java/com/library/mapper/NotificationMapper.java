package com.library.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.library.entity.Notification;
import org.apache.ibatis.annotations.Mapper;

/**
 * Notification 表 Mapper：继承 BaseMapper 获得通用 CRUD 能力
 */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {
}

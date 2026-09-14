package com.ams.platform.observability.mapper;

import com.ams.platform.observability.AppLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 应用日志 Mapper（端侧报错 + 后端异常）。 */
@Mapper
public interface AppLogMapper extends BaseMapper<AppLog> {
}

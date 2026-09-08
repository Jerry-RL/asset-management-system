package com.ams.modules.migration.mapper;

import com.ams.modules.migration.entity.MigrationBatch;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MigrationBatchMapper extends BaseMapper<MigrationBatch> {
}

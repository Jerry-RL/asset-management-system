package com.ams.modules.billing.mapper;

import com.ams.modules.billing.entity.Bill;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BillMapper extends BaseMapper<Bill> {
}

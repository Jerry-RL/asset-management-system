package com.ams.modules.invoice.mapper;

import com.ams.modules.invoice.entity.Invoice;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InvoiceMapper extends BaseMapper<Invoice> {
}

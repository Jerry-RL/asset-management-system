package com.ams.modules.contract.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 合同模板：HTML 正文 + {{slot}} 数据插槽，用于快速生成合同并导出 Word。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("contract_template")
public class ContractTemplate extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String templateCode;
    private String name;
    /** lease / transfer / vacate / other */
    private String contractType;
    private String description;
    private String contentHtml;
    /** JSON 数组，声明使用的插槽 key */
    private String slotsJson;
    private Integer version;
    private Boolean enabled;
}

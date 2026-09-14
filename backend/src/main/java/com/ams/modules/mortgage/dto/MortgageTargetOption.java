package com.ams.modules.mortgage.dto;

import lombok.Data;

/**
 * 抵押标的选题项（{@code GET /mortgages/target-options}）。
 *
 * <p>三种标的类型共用一个 DTO，而不是三个端点：前端只有一个下拉，
 * 它对三种类型的差异没有任何兴趣 —— 它要的只是「选哪个 + 显示什么」。
 *
 * <p>与 {@link MortgageRecordView} 同样只回原料不拼 label，理由见该类注释。
 */
@Data
public class MortgageTargetOption {

    private Long targetId;

    /** 标的自身的名字：项目名 / 分区名 / 资产名。 */
    private String name;

    /** 分区 / 资产标的的上级项目名。 */
    private String projectName;

    /** 资产标的的所属分区名。 */
    private String zoneName;

    private Integer floorNo;

    private String assetNo;
}

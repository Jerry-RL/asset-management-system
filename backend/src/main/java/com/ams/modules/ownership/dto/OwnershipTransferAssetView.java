package com.ams.modules.ownership.dto;

import lombok.Data;

/**
 * 权属流转详情里的一行资产。
 *
 * <p>{@code projectName} / {@code zoneName} / {@code floorNo} 是为**前端 4 段展示**
 * （{@code 项目 · 分区 · 楼层 · 资产名称}）准备的原料，由服务端一次查全 —— 让前端为每行再发请求
 * 会在展开 20 个资产时打出 20 个请求。
 */
@Data
public class OwnershipTransferAssetView {

    private Long assetId;
    private String assetNo;
    private String assetName;

    private String projectName;
    private String zoneName;
    private Integer floorNo;

    private Long fromPropertyCompanyId;
    private Long fromOperatingCompanyId;
}

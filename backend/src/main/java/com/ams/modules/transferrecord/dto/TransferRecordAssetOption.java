package com.ams.modules.transferrecord.dto;

import lombok.Data;

/**
 * 资产下拉项（{@code GET /asset-transfer-records/asset-options}）。
 *
 * <p>刻意只回原料不拼 label：label 的拼接规则（空段如何跳过）属于展示层，
 * 放在服务端会让「改一次文案要发一次后端版本」。
 */
@Data
public class TransferRecordAssetOption {

    private Long assetId;
    private String assetNo;
    private String name;

    private String projectName;
    private String zoneName;
    private Integer floorNo;
}

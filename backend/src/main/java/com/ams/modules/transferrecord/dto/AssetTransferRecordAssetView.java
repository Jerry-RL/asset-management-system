package com.ams.modules.transferrecord.dto;

import lombok.Data;

/**
 * 资产调拨记录详情里的一行资产。
 *
 * <p>{@code projectName} / {@code zoneName} / {@code floorNo} 是为**前端 4 段展示**
 * （{@code 项目 - 分区 - 楼层 - 资产名称}）准备的原料，由服务端一次查全 —— 让前端为每行再发请求
 * 会在展开 20 个资产时打出 20 个请求。
 *
 * <p>{@code fromDepartmentName} / {@code fromUserName} 是**生效那一刻**的旧值（明细行快照），
 * 不是资产当前的责任部门 —— 生效后 asset 上已是新值，现读会全部显示成新部门。
 */
@Data
public class AssetTransferRecordAssetView {

    private Long assetId;
    private String assetNo;
    private String assetName;

    private String projectName;
    private String zoneName;
    private Integer floorNo;

    private Long fromDepartmentId;
    private String fromDepartmentName;
    private Long fromUserId;
    private String fromUserName;
}

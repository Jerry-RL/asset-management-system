package com.ams.modules.assetoperator.dto;

import lombok.Data;

/**
 * 人员下拉候选（V60）。
 *
 * <p><b>为什么不复用 {@code GET /system/users}</b>：那个端点要求 {@code org.user:view}，
 * 而维护运营人员档案的人未必持有人员维护权限 —— 让他们因为缺一个无关权限就选不到人，
 * 功能等于不存在。与资产调拨记录另开 `asset-options` 是同一个理由。
 *
 * <p>返回字段只到「能认出是哪个人」为止（姓名 / 手机 / 公司 / 部门），
 * 不含账号密码等敏感列。
 */
@Data
public class AssetOperatorUserOption {

    private Long userId;

    private String name;

    private String phone;

    private Long companyId;

    private String companyName;

    private Long departmentId;

    private String departmentName;
}

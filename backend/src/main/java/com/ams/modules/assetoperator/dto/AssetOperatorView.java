package com.ams.modules.assetoperator.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 资产运营人员视图（列表与详情共用）。
 *
 * <p><b>列表不返回 {@link #scopes} 明细、详情才返回</b>（{@code withScopes} 开关）：
 * 列表页每行只需显示「项目 n / 分区 n / 资产 n」的计数与几个标签，
 * 把每行的全部范围名都拉出来会让一页 10 行的响应里塞进上百个对象。
 */
@Data
public class AssetOperatorView {

    private Long id;

    /** 人员（sys_user.id）。 */
    private Long userId;

    private String userName;

    private String phone;

    /** 人员所属公司（{@code user.company_id}）与其名称。 */
    private Long companyId;

    private String companyName;

    private Long departmentId;

    private String departmentName;

    /** 角色 id 列表（与 {@link #roleNames} 一一对应）。 */
    private List<Long> roleIds = new ArrayList<>();

    private List<String> roleNames = new ArrayList<>();

    /** 运营范围（仅详情返回；列表为 null）。 */
    private List<AssetOperatorScopeView> scopes;

    /** 运营范围计数，列表与详情都返回（列表靠它显示「项目 n / 分区 n / 资产 n」）。 */
    private int projectCount;
    private int zoneCount;
    private int assetCount;

    private Integer status;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

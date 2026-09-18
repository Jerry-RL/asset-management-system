package com.ams.modules.assetoperator.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 新增 / 编辑资产运营人员入参（V60）。
 *
 * <p>四个字段对应需求的四项填写内容：人员选择（{@link #userId}）、角色选择
 * （{@link #roleIds}）、资产运营范围（{@link #scopes}）、备注。
 *
 * <p><b>角色与范围都是「全量替换」语义</b>：请求体里给的是编辑后的完整集合，
 * 不是增量。这样编辑两次相同内容的结果幂等，也不需要前端算差集 ——
 * 与 `asset_transfer_record_asset` 的明细处理同口径。
 */
@Data
public class AssetOperatorInput {

    /** 人员（sys_user.id）必填。 */
    private Long userId;

    /** 角色（role.id），至少 1 个。 */
    private List<Long> roleIds = new ArrayList<>();

    /** 资产运营范围（项目 / 分区 / 资产，可混选），至少 1 条。 */
    private List<AssetOperatorScopeRef> scopes = new ArrayList<>();

    /** 启用状态：1 启用 / 0 停用。为空按启用处理。 */
    private Integer status;

    private String remark;
}

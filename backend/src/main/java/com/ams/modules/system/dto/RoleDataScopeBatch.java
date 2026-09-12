package com.ams.modules.system.dto;

import java.util.List;
import lombok.Data;

/**
 * 保存角色数据范围排除清单的请求体（设计 4.5 的 {@code PUT /system/roles/{roleId}/data-scope}）。
 *
 * <p>语义与全站其他授权相反：<strong>列表里的公司是被排除的</strong>，命中后连同整棵下级子树
 * 一起从可访问范围扣除。因此请求体刻意不叫 {@code allowedCompanyIds}。
 */
@Data
public class RoleDataScopeBatch {

    /** 被排除的公司 id（原值，未展开子树 —— 服务端负责展开）。 */
    private List<Long> companyIds;
}

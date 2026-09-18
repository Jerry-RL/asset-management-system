package com.ams.modules.assetoperator.dto;

import lombok.Data;

/**
 * 资产运营范围引用：范围**类型** + 范围 **id** 的二元组。
 *
 * <p>用值对象而不是裸的两个参数：范围在请求体里是**数组**，校验与去重都要以
 * 「(type, id) 二元组」为单位（`project:3` 与 `asset:3` 是两条不同的范围），
 * 散着传会让每个调用点各自拼 key，拼法必然漂移。
 */
@Data
public class AssetOperatorScopeRef {

    /** project / zone / asset。 */
    private String scopeType;

    /** 范围 id：project.id / project_zone.id / asset.id。 */
    private Long scopeId;
}

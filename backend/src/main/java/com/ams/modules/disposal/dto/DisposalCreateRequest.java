package com.ams.modules.disposal.dto;

import com.ams.modules.disposal.entity.DisposalOrder;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 处置申请（单条新建）：{@link DisposalOrderInput} 的写形状 + 归属资产。
 *
 * <p>资产的处置单有两条写入口，字段搬运共用父类（{@link DisposalOrderInput#applyTo}）：
 *
 * <ul>
 *   <li>{@code POST /disposals} —— 单条新建（本类）；</li>
 *   <li>{@code PUT /assets/{assetId}/disposals} —— 资产表单第 3 步的一次性同步（父类 + id）。</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DisposalCreateRequest extends DisposalOrderInput {

    private Long assetId;

    public DisposalOrder toOrder() {
        DisposalOrder order = new DisposalOrder();
        order.setAssetId(assetId);
        applyTo(order);
        return order;
    }
}

package com.ams.modules.disposal.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 资产处置单的**全量同步**请求体（{@code PUT /assets/{assetId}/disposals}）。
 *
 * <p>语义与 record-sheet 一致：请求体是「这个资产当前应有的处置单列表」，
 * 服务端按 id 做增量 diff —— 有 id 的改草稿、无 id 的新建成草稿、
 * 服务端有而请求体没有的**草稿**删掉。非草稿单不参与写入（审批中及之后的单子
 * 只能通过流程端点推进，见 {@code DisposalService.syncForAsset}）。
 *
 * <p>之所以用「全量同步」而不是前端逐条调 {@code POST/PUT/DELETE}：
 * 一次保存 = 一次请求 = 一个事务，不会出现「新增成功、删除失败」的半成品状态，
 * 也让资产表单的第 3 步与项目/分区那两个台账段共用同一套「改动攒着、保存时一起提交」的心智模型。
 */
@Data
public class DisposalOrderSyncRequest {

    private List<DisposalOrderInput> records = new ArrayList<>();
}

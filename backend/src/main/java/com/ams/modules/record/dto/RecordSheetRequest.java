package com.ams.modules.record.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 后续记录聚合写请求（设计 §5.2）。
 *
 * <p><strong>三段都是「全量提交」语义</strong>：带 id 的更新、无 id 的新增、**未出现的软删**。
 * 因此前端必须提交完整列表，不能只提交变更项。
 *
 * <p>资产主体：{@link #disposalRecords} 段被服务端忽略（用 {@code disposal_order}）。
 * {@link #sourceInfo} 传 {@code null} 或整体缺失表示不修改。
 */
@Data
public class RecordSheetRequest {

    private List<ReceiveInput> receives = new ArrayList<>();

    private SourceInput sourceInfo;

    private List<DisposalInput> disposalRecords = new ArrayList<>();
}

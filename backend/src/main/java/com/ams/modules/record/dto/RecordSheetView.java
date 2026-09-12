package com.ams.modules.record.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 后续记录聚合读视图（设计 §5.2）。
 *
 * <p>{@link #disposals} 只在资产主体下非空（来自 {@code disposal_order}）；
 * {@link #disposalRecords} 只在项目 / 分区下非空。两者**不同时非空**，
 * 前端按 ownerType 决定渲染「只读流程面板」还是「可编辑台账」。
 *
 * <p>集合字段一律初始化，避免前端为「接口返回 null」写防御分支。
 */
@Data
public class RecordSheetView {

    private List<ReceiveInput> receives = new ArrayList<>();

    /** 没有来源明细时为 null（不是空对象），前端据此渲染空表单。 */
    private SourceInput sourceInfo;

    private List<DisposalInput> disposalRecords = new ArrayList<>();

    private List<DisposalOrderView> disposals = new ArrayList<>();
}

package com.ams.modules.disposal.dto;

import com.ams.modules.disposal.entity.DisposalOrder;
import com.ams.modules.record.dto.AttachmentRef;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 一条处置单的**写形状**：单条新建（{@link DisposalCreateRequest}）与资产表单的批量同步
 * （{@code PUT /assets/{assetId}/disposals}）共用它，避免两处各写一遍字段搬运。
 *
 * <p>刻意**不含 assetId 与 status**：
 *
 * <ul>
 *   <li>归属由路径决定（批量同步不接受请求体里的 assetId，与 record-sheet「归属由服务端赋值」同一口径）；</li>
 *   <li>status 由流程驱动（新建强制 {@code draft}，流转走 {@code /submit} 等端点），
 *       客户端无法直造一个「已审批」的单子。</li>
 * </ul>
 *
 * <p>{@link #id} 只在批量同步里有意义：为空表示新增（服务端建成草稿），
 * 有值表示要改哪一条（且只有草稿会被改，见 {@code DisposalService.syncForAsset}）。
 */
@Data
public class DisposalOrderInput {

    private Long id;

    private String disposalType;

    private String reason;

    private BigDecimal assessedValue;

    private BigDecimal bookValue;

    /** 处置金额。{@code disposal_order.actual_amount} 的存量单位未在库中标注，本设计不做换算。 */
    private BigDecimal actualAmount;

    private String counterparty;

    private Long disposalUserId;

    /** 姓名快照，内员选择时由服务端按 userId 回填。 */
    private String disposalUserName;

    private LocalDate disposalDate;

    private String remark;

    /** 附件关联：文件本体走 {@code POST /files/upload}，这里只带 fileId 与顺序。 */
    private List<AttachmentRef> attachments = new ArrayList<>();

    /** 把业务字段搬到实体上。不动 assetId / status / id：三者都不是客户端可写的。 */
    public void applyTo(DisposalOrder order) {
        order.setDisposalType(disposalType);
        order.setReason(reason);
        order.setAssessedValue(assessedValue);
        order.setBookValue(bookValue);
        order.setActualAmount(actualAmount);
        order.setCounterparty(counterparty);
        order.setDisposalUserId(disposalUserId);
        order.setDisposalUserName(disposalUserName);
        order.setDisposalDate(disposalDate);
        order.setRemark(remark);
    }
}

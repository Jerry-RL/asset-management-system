package com.ams.modules.record;

/**
 * 处置级联端口（由处置上下文实现，供后续记录在「项目 / 分区新增处置台账」时回调）。
 *
 * <h2>为什么接口定义在调用方（record），而不是 disposal</h2>
 * 后续记录要触发的语义是「处置已经发生」——这属于处置上下文。若 {@code RecordSheetService}
 * 直接依赖处置模块的服务类，就形成 {@code record ↔ disposal} 的**包级环**
 * （处置侧本就要依赖 {@code RecordSheetService} 写附件与单子），
 * 而「同一语义两处判定漂移」与「跨上下文直调」正是 {@code 领域划分与限界上下文设计} §6.3
 * 列为必须收敛的两类问题。把接口放在**调用方**、由处置上下文实现，依赖方向变成单向：
 * {@code disposal → record}（读端口），record 不再认识 disposal。
 *
 * <h2>调用时机</h2>
 * 只在**新增**一条 {@code biz_disposal_record}（项目 / 分区处置登记）时回调。处置不可逆：
 * 编辑 / 删除处置台账**不回滚**已级联的资产状态（要把产权公司还回去得走权属流转重新登记）。
 */
public interface DisposalCascadePort {

    /** 处置对象层级：资产。 */
    String TARGET_ASSET = "asset";

    /** 处置对象层级：项目。 */
    String TARGET_PROJECT = "project";

    /** 处置对象层级：项目分区。 */
    String TARGET_ZONE = "zone";

    /**
     * 把处置对象下的资产级联为「已处置」（脱离原产权公司）。
     *
     * <p><b>实现侧必须幂等</b>：已退出（{@code lifecycle_status='exited'}）的资产要被跳过，
     * 同一资产重复级联不得产生第二批副作用。
     *
     * @param targetType     {@link #TARGET_ASSET} / {@link #TARGET_PROJECT} / {@link #TARGET_ZONE}
     * @param targetId       处置对象 id（asset.id / project.id / project_zone.id）
     * @param snapshot       处置信息快照（来源单据可能被软删，台账必须自洽）
     * @param sourceOrderId  资产级来源 {@code disposal_order.id}；项目 / 分区级为 null
     * @param sourceRecordId 项目 / 分区级来源 {@code biz_disposal_record.id}；资产级为 null
     * @return 本次真正被处置的资产数（不含此前已退出的资产）
     */
    int cascadeDispose(String targetType, Long targetId, DisposalCascadeSnapshot snapshot,
            Long sourceOrderId, Long sourceRecordId);
}

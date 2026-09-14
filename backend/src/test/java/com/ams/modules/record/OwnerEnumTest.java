package com.ams.modules.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ams.common.exception.AppException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 宿主枚举的权限码映射（设计 §5.2 / §5.3）。
 *
 * <p>这张映射表是「分区复用 asset.project 权限码、资产用 asset.ledger」这个决策的**唯一落点**：
 * 一旦某个枚举值的 menuCode 写错，对应主体的所有接口会对所有人 403（菜单码不存在时
 * PermissionRegistry 会直接让应用启动失败，这反而是好事）。本用例把映射钉死在测试里，
 * 让改错的人看到的是断言失败而不是线上 403。
 */
class OwnerEnumTest {

    @Test
    @DisplayName("三种主体的 code 与权限码映射")
    void recordOwnerTypeMapsToMenuCode() {
        assertThat(RecordOwnerType.ASSET.code()).isEqualTo("asset");
        assertThat(RecordOwnerType.ASSET.menuCode()).isEqualTo("asset.ledger");
        assertThat(RecordOwnerType.PROJECT.code()).isEqualTo("project");
        assertThat(RecordOwnerType.PROJECT.menuCode()).isEqualTo("asset.project");
        // 分区是项目配置的一部分，刻意复用资产项目权限码，不新增菜单
        assertThat(RecordOwnerType.ZONE.code()).isEqualTo("zone");
        assertThat(RecordOwnerType.ZONE.menuCode()).isEqualTo("asset.project");
    }

    @Test
    @DisplayName("未知宿主 code 拒绝，而不是静默退回某个默认主体")
    void unknownOwnerCodeIsRejected() {
        assertThatThrownBy(() -> RecordOwnerType.fromCode("tenant"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("tenant");
        assertThat(RecordOwnerType.ofCode(null)).isEmpty();
        assertThat(RecordOwnerType.ofCode("asset")).contains(RecordOwnerType.ASSET);
    }

    @Test
    @DisplayName("附件的直接宿主与 biz_type 映射")
    void attachmentOwnerMapsToBizType() {
        assertThat(AttachmentOwner.RECEIVE_RECORD.code()).isEqualTo("receive_record");
        assertThat(AttachmentOwner.RECEIVE_RECORD.bizType()).isEqualTo("receive_doc");
        assertThat(AttachmentOwner.RECEIVE_ISSUE.code()).isEqualTo("receive_issue");
        assertThat(AttachmentOwner.RECEIVE_ISSUE.bizType()).isEqualTo("issue_scene");
        assertThat(AttachmentOwner.SOURCE_INFO.code()).isEqualTo("source_info");
        assertThat(AttachmentOwner.SOURCE_INFO.bizType()).isEqualTo("source_attach");
        assertThat(AttachmentOwner.DISPOSAL_RECORD.code()).isEqualTo("disposal_record");
        assertThat(AttachmentOwner.DISPOSAL_RECORD.bizType()).isEqualTo("disposal_attach");
        assertThat(AttachmentOwner.DISPOSAL_ORDER.code()).isEqualTo("disposal_order");
        assertThat(AttachmentOwner.DISPOSAL_ORDER.bizType()).isEqualTo("disposal_attach");
        assertThat(AttachmentOwner.COST_RECORD.code()).isEqualTo("cost_record");
        assertThat(AttachmentOwner.COST_RECORD.bizType()).isEqualTo("cost_attach");
        assertThat(AttachmentOwner.EVALUATION_INFO.code()).isEqualTo("evaluation_info");
        assertThat(AttachmentOwner.EVALUATION_INFO.bizType()).isEqualTo("evaluation_attach");
        assertThat(AttachmentOwner.OWNERSHIP_TRANSFER.code()).isEqualTo("ownership_transfer");
        assertThat(AttachmentOwner.OWNERSHIP_TRANSFER.bizType()).isEqualTo("transfer_attach");
        // V55 资产调拨记录：宿主码与 bizType 都不能与权属流转撞车 —— 两者都是一单多资产，
        // 撞了之后附件列表页按 bizType 分组会把两个模块的附件混在一起
        assertThat(AttachmentOwner.ASSET_TRANSFER_RECORD.code())
                .isEqualTo("asset_transfer_record");
        assertThat(AttachmentOwner.ASSET_TRANSFER_RECORD.bizType()).isEqualTo("alloc_attach");
        // V56 抵押记录：标的可能是项目 / 分区 / 资产，宿主码与 bizType 同样不能与既有撞车
        assertThat(AttachmentOwner.MORTGAGE.code()).isEqualTo("mortgage");
        assertThat(AttachmentOwner.MORTGAGE.bizType()).isEqualTo("mortgage_attach");
    }
}

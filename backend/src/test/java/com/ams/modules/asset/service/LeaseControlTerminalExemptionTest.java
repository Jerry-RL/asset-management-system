package com.ams.modules.asset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.common.exception.AppException;
import com.ams.modules.asset.LeaseControlStatus;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.LeaseControlLog;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.LeaseControlLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 租控「终态豁免」推进（{@code transitionUnlessExited}）。
 *
 * <p>存在的理由：{@code exited} 是状态机终态且不允许自迁移，而**已退出的资产仍可能需要
 * 登记新的处置单据**（分次处置；或项目 / 分区级处置 V57 级联先退出、随后补登记资产级处置单）。
 * 若这一步照旧抛 409，使用者看到的是一句「租控状态不允许从 exited 迁移到 disposing」——
 * 而真实原因只是「重复写入同一终态被状态机判成非法」，与被处置资产能不能再登记处置无关。
 *
 * <p>反向用例（{@code leased → disposing} 仍抛错）与正向用例**同等重要**：
 * 豁免一旦放宽成「处置写不进就跳过」，就会把「在租资产须先退租才能处置」这条**真实**规则
 * 一起吃掉 —— 那会让在租资产被静默处置。
 *
 * <p>桩不模拟 SQL：{@code LambdaQueryWrapper} 的条件读不出来，故「查出来什么」显式桩定，
 * 每个用例都配反向对照，避免「恰好返回 null / 空」让断言因为错误的原因通过。
 */
class LeaseControlTerminalExemptionTest {

    private static final long ASSET_ID = 7L;
    private static final long ORDER_ID = 55L;

    private final AssetMapper assetMapper = mock(AssetMapper.class);
    private final LeaseControlLogMapper logMapper = mock(LeaseControlLogMapper.class);

    private final LeaseControlService service =
            new LeaseControlService(assetMapper, logMapper);

    // ------------------------------------------------------------------
    // 正向：终态豁免
    // ------------------------------------------------------------------

    @Test
    @DisplayName("资产已是 exited：跳过写入并返回 false —— 不再抛「exited 不能自迁移」的 409")
    void alreadyExitedIsSkipped() {
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset(LeaseControlStatus.EXITED));

        boolean written = service.transitionUnlessExited(
                ASSET_ID, LeaseControlStatus.DISPOSING, "disposal", ORDER_ID, "处置审批通过");

        assertThat(written).isFalse();
        verify(assetMapper, never()).updateById(any(Asset.class));
        // 没有发生状态变化，就不该留租控日志行（否则日志里会出现「exited → exited」的伪迁移）
        verify(logMapper, never()).insert(any(LeaseControlLog.class));
    }

    @Test
    @DisplayName("资产已是 exited 时重复推进到 exited：同样跳过（分次处置的第二次完成）")
    void repeatedCompletionIsSkipped() {
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset(LeaseControlStatus.EXITED));

        boolean written = service.transitionUnlessExited(
                ASSET_ID, LeaseControlStatus.EXITED, "disposal", ORDER_ID, "处置完成，资产已退出");

        assertThat(written).isFalse();
        verify(assetMapper, never()).updateById(any(Asset.class));
    }

    // ------------------------------------------------------------------
    // 正向：正常迁移仍然照旧
    // ------------------------------------------------------------------

    @Test
    @DisplayName("资产为空置：正常写入并返回 true，租控日志留痕")
    void vacantTransitionsNormally() {
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset(LeaseControlStatus.VACANT));
        when(assetMapper.updateById(any(Asset.class))).thenReturn(1);

        boolean written = service.transitionUnlessExited(
                ASSET_ID, LeaseControlStatus.DISPOSING, "disposal", ORDER_ID, "处置审批通过");

        assertThat(written).isTrue();
        ArgumentCaptor<Asset> updated = ArgumentCaptor.forClass(Asset.class);
        verify(assetMapper).updateById(updated.capture());
        assertThat(updated.getValue().getLeaseControlStatus())
                .isEqualTo(LeaseControlStatus.DISPOSING);
        ArgumentCaptor<LeaseControlLog> log = ArgumentCaptor.forClass(LeaseControlLog.class);
        verify(logMapper).insert(log.capture());
        assertThat(log.getValue().getFromStatus()).isEqualTo(LeaseControlStatus.VACANT);
        assertThat(log.getValue().getToStatus()).isEqualTo(LeaseControlStatus.DISPOSING);
    }

    @Test
    @DisplayName("处置中 → 已退出：正常写入（首次处置完成的主路径）")
    void disposingToExitedTransitionsNormally() {
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset(LeaseControlStatus.DISPOSING));
        when(assetMapper.updateById(any(Asset.class))).thenReturn(1);

        boolean written = service.transitionUnlessExited(
                ASSET_ID, LeaseControlStatus.EXITED, "disposal", ORDER_ID, "处置完成");

        assertThat(written).isTrue();
        verify(logMapper).insert(any(LeaseControlLog.class));
    }

    // ------------------------------------------------------------------
    // 反向：豁免不得放宽成「写不进就跳过」
    // ------------------------------------------------------------------

    @Test
    @DisplayName("根因固化：状态机确实拒绝 exited → disposing / exited，这正是需要豁免入口的原因")
    void stateMachineIndeedRejectsTerminalTransitions() {
        // 若哪天状态机改成允许自迁移 / 迁出终态，本用例会变红 —— 那时应重新评估
        // transitionUnlessExited 是否还需要存在，而不是让它悄悄变成多余分支
        assertThat(LeaseControlStatus.canTransition(
                LeaseControlStatus.EXITED, LeaseControlStatus.DISPOSING)).isFalse();
        assertThat(LeaseControlStatus.canTransition(
                LeaseControlStatus.EXITED, LeaseControlStatus.EXITED)).isFalse();
    }

    @Test
    @DisplayName("在租资产 → 处置中：仍然 409 —— 豁免只针对终态，不得把真实规则一起吃掉")
    void leasedToDisposingStillRejected() {
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset(LeaseControlStatus.LEASED));

        assertThatThrownBy(() -> service.transitionUnlessExited(
                ASSET_ID, LeaseControlStatus.DISPOSING, "disposal", ORDER_ID, "处置审批通过"))
                .isInstanceOf(AppException.class);

        verify(assetMapper, never()).updateById(any(Asset.class));
        verify(logMapper, never()).insert(any(LeaseControlLog.class));
    }

    @Test
    @DisplayName("资产不存在：404 —— 不静默跳过（否则单据会指向一个不存在的资产）")
    void missingAssetIsNotFound() {
        when(assetMapper.selectById(ASSET_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.transitionUnlessExited(
                ASSET_ID, LeaseControlStatus.DISPOSING, "disposal", ORDER_ID, "处置审批通过"))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("乐观锁冲突：递增版本时更新 0 行必须显式 409，不得当作成功")
    void optimisticLockConflictFails() {
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset(LeaseControlStatus.VACANT));
        when(assetMapper.updateById(any(Asset.class))).thenReturn(0);

        assertThatThrownBy(() -> service.transitionUnlessExited(
                ASSET_ID, LeaseControlStatus.DISPOSING, "disposal", ORDER_ID, "处置审批通过"))
                .isInstanceOf(AppException.class);

        verify(logMapper, never()).insert(any(LeaseControlLog.class));
    }

    private Asset asset(String leaseControlStatus) {
        Asset asset = new Asset();
        asset.setId(ASSET_ID);
        asset.setLeaseControlStatus(leaseControlStatus);
        return asset;
    }
}

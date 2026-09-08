package com.ams.modules.asset;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 租控状态机测试（SRS §4.24.1）。
 */
class LeaseControlStatusTest {

    @Test
    void vacantCanGoToLeasingSelfUseOccupationDisposal() {
        assertThat(LeaseControlStatus.canTransition(LeaseControlStatus.VACANT, LeaseControlStatus.LEASING)).isTrue();
        assertThat(LeaseControlStatus.canTransition(LeaseControlStatus.VACANT, LeaseControlStatus.SELF_USE)).isTrue();
        assertThat(LeaseControlStatus.canTransition(LeaseControlStatus.VACANT, LeaseControlStatus.OCCUPIED)).isTrue();
        assertThat(LeaseControlStatus.canTransition(LeaseControlStatus.VACANT, LeaseControlStatus.DISPOSING)).isTrue();
    }

    @Test
    void leasedCanGoToVacatingOrPartial() {
        assertThat(LeaseControlStatus.canTransition(LeaseControlStatus.LEASED, LeaseControlStatus.VACATING)).isTrue();
        assertThat(LeaseControlStatus.canTransition(LeaseControlStatus.LEASED, LeaseControlStatus.PARTIAL_LEASED)).isTrue();
    }

    @Test
    void exitedIsTerminal() {
        assertThat(LeaseControlStatus.canTransition(LeaseControlStatus.EXITED, LeaseControlStatus.VACANT)).isFalse();
        assertThat(LeaseControlStatus.canTransition(LeaseControlStatus.EXITED, LeaseControlStatus.LEASED)).isFalse();
    }

    @Test
    void sameStateTransitionIsRejected() {
        assertThat(LeaseControlStatus.canTransition(LeaseControlStatus.VACANT, LeaseControlStatus.VACANT)).isFalse();
    }
}

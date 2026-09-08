package com.ams.modules.contract;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 合同状态机测试（SRS §4.24.9）。
 */
class ContractStatusTest {

    @Test
    void draftGoesToApprovingOrVoided() {
        assertThat(ContractStatus.canTransition(ContractStatus.DRAFT, ContractStatus.APPROVING)).isTrue();
        assertThat(ContractStatus.canTransition(ContractStatus.DRAFT, ContractStatus.VOIDED)).isTrue();
        assertThat(ContractStatus.canTransition(ContractStatus.DRAFT, ContractStatus.ACTIVE)).isFalse();
    }

    @Test
    void activeLifecycle() {
        assertThat(ContractStatus.canTransition(ContractStatus.ACTIVE, ContractStatus.EXPIRING)).isTrue();
        assertThat(ContractStatus.canTransition(ContractStatus.ACTIVE, ContractStatus.RENEWABLE)).isTrue();
        assertThat(ContractStatus.canTransition(ContractStatus.ACTIVE, ContractStatus.TERMINATING)).isTrue();
        assertThat(ContractStatus.canTransition(ContractStatus.ACTIVE, ContractStatus.TERMINATED)).isTrue();
    }

    @Test
    void renewableCanBeRenewedOrExpire() {
        assertThat(ContractStatus.canTransition(ContractStatus.RENEWABLE, ContractStatus.ACTIVE)).isTrue();
        assertThat(ContractStatus.canTransition(ContractStatus.RENEWABLE, ContractStatus.EXPIRED)).isTrue();
    }

    @Test
    void expiredCanBeTerminatedOrRenewed() {
        assertThat(ContractStatus.canTransition(ContractStatus.EXPIRED, ContractStatus.TERMINATED)).isTrue();
        assertThat(ContractStatus.canTransition(ContractStatus.EXPIRED, ContractStatus.RENEWABLE)).isTrue();
    }
}

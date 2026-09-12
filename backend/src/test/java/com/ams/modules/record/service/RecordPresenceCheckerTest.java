package com.ams.modules.record.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ams.modules.record.RecordOwnerType;
import com.ams.modules.record.mapper.DisposalRecordMapper;
import com.ams.modules.record.mapper.ReceiveIssueMapper;
import com.ams.modules.record.mapper.ReceiveRecordMapper;
import com.ams.modules.record.mapper.SourceInfoMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「分区是否已有后续记录」的判定（设计 §7.3）。
 *
 * <p>只看记录、不看附件：附件总是挂在某条记录或某个字段上，没有脱离记录的孤立附件，
 * 因此「没有记录」等价于「没有附件」。这一点如果理解反了，会让「删掉附件的空记录」
 * 这种场景被判成不可删。
 *
 * <p>用 {@code > 0} 而不是 {@code == 0} 来判定存在性：MyBatis-Plus 的 {@code selectCount}
 * 返回 {@code Long}，桩里的 {@code null} 会让自动拆箱抛 NPE —— 这是本项目里出现过的真实缺陷，
 * 因此实现必须显式对 null 做安全处理。
 */
class RecordPresenceCheckerTest {

    private final ReceiveRecordMapper receiveRecordMapper = mock(ReceiveRecordMapper.class);
    private final ReceiveIssueMapper receiveIssueMapper = mock(ReceiveIssueMapper.class);
    private final SourceInfoMapper sourceInfoMapper = mock(SourceInfoMapper.class);
    private final DisposalRecordMapper disposalRecordMapper = mock(DisposalRecordMapper.class);

    private final RecordPresenceChecker checker = new RecordPresenceChecker(
            receiveRecordMapper, receiveIssueMapper, sourceInfoMapper, disposalRecordMapper);

    @Test
    @DisplayName("四张记录表任一有数据即为「已有记录」")
    void anyRecordTypeMeansPresent() {
        when(receiveRecordMapper.selectCount(any())).thenReturn(0L);
        when(receiveIssueMapper.selectCount(any())).thenReturn(0L);
        when(sourceInfoMapper.selectCount(any())).thenReturn(0L);
        when(disposalRecordMapper.selectCount(any())).thenReturn(1L);

        assertThat(checker.hasRecords(RecordOwnerType.ZONE, 9L)).isTrue();
    }

    @Test
    @DisplayName("四张记录表都为空则为「无记录」")
    void noRowsMeansAbsent() {
        when(receiveRecordMapper.selectCount(any())).thenReturn(0L);
        when(receiveIssueMapper.selectCount(any())).thenReturn(0L);
        when(sourceInfoMapper.selectCount(any())).thenReturn(0L);
        when(disposalRecordMapper.selectCount(any())).thenReturn(0L);

        assertThat(checker.hasRecords(RecordOwnerType.PROJECT, 1L)).isFalse();
    }

    @Test
    @DisplayName("资产主体不查处置台账（资产的处置在 disposal_order，不属于记录表）")
    void assetOwnerSkipsDisposalRecordTable() {
        when(receiveRecordMapper.selectCount(any())).thenReturn(0L);
        when(receiveIssueMapper.selectCount(any())).thenReturn(0L);
        when(sourceInfoMapper.selectCount(any())).thenReturn(0L);

        assertThat(checker.hasRecords(RecordOwnerType.ASSET, 7L)).isFalse();
    }

    @Test
    @DisplayName("计数返回 null（未命中桩）时不抛 NPE，按「无记录」处理")
    void nullCountIsTreatedAsAbsent() {
        when(receiveRecordMapper.selectCount(any())).thenReturn(null);
        when(receiveIssueMapper.selectCount(any())).thenReturn(0L);
        when(sourceInfoMapper.selectCount(any())).thenReturn(0L);
        when(disposalRecordMapper.selectCount(any())).thenReturn(null);

        assertThat(checker.hasRecords(RecordOwnerType.ZONE, 9L)).isFalse();
    }
}

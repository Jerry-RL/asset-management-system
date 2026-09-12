package com.ams.modules.record.service;

import com.ams.modules.record.RecordOwnerType;
import com.ams.modules.record.entity.DisposalRecord;
import com.ams.modules.record.entity.ReceiveRecord;
import com.ams.modules.record.entity.SourceInfo;
import com.ams.modules.record.mapper.DisposalRecordMapper;
import com.ams.modules.record.mapper.ReceiveIssueMapper;
import com.ams.modules.record.mapper.ReceiveRecordMapper;
import com.ams.modules.record.mapper.SourceInfoMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

/**
 * 「该宿主是否已有后续记录」的判定（设计 §7.3）。
 *
 * <p>抽成独立组件而不是塞进 {@code RecordSheetService}：分区删除守卫在
 * {@code modules.asset} 里，如果让它依赖 {@code RecordSheetService}，就会让
 * 「资产服务依赖记录服务、记录服务又依赖资产 Mapper」变成一个循环依赖的雏形。
 * 这里只依赖四个 Mapper，方向单一。
 *
 * <p><b>只看记录、不看附件</b>：附件必然挂在某条记录上（接收信息 / 遗留问题 / 来源明细）
 * 或某个主体的处置单上，没有脱离记录的孤立附件。所以「没有记录」蕴含「没有附件」。
 *
 * <p>实现里所有计数都走 {@link #present(Long)} 而不是直接比较：MyBatis-Plus 的
 * {@code selectCount} 返回包装类型 {@code Long}，在某些桩 / 驱动下可能为 {@code null}，
 * 直接 {@code > 0} 会 NPE —— 而这里最坏的结果只是「判定为无记录」，不该让整个删除接口崩掉。
 */
@Service
public class RecordPresenceChecker {

    private final ReceiveRecordMapper receiveRecordMapper;
    private final ReceiveIssueMapper receiveIssueMapper;
    private final SourceInfoMapper sourceInfoMapper;
    private final DisposalRecordMapper disposalRecordMapper;

    public RecordPresenceChecker(
            ReceiveRecordMapper receiveRecordMapper,
            ReceiveIssueMapper receiveIssueMapper,
            SourceInfoMapper sourceInfoMapper,
            DisposalRecordMapper disposalRecordMapper) {
        this.receiveRecordMapper = receiveRecordMapper;
        this.receiveIssueMapper = receiveIssueMapper;
        this.sourceInfoMapper = sourceInfoMapper;
        this.disposalRecordMapper = disposalRecordMapper;
    }

    /** 分区是否有后续记录（供分区删除守卫调用）。 */
    public boolean hasRecordsForZone(Long zoneId) {
        return hasRecords(RecordOwnerType.ZONE, zoneId);
    }

    /** 指定主体是否有后续记录。 */
    public boolean hasRecords(RecordOwnerType type, Long ownerId) {
        if (ownerId == null) {
            return false;
        }
        if (present(receiveRecordMapper.selectCount(new LambdaQueryWrapper<ReceiveRecord>()
                .eq(ReceiveRecord::getOwnerType, type.code())
                .eq(ReceiveRecord::getOwnerId, ownerId)
                .apply("deleted_at IS NULL")))) {
            return true;
        }
        // 遗留问题挂在接收信息下，接收信息被软删时其 issue 也一并软删；
        // 但 issue 可能先于父记录被单独删空，所以这里不单独查 issue：
        // 「有 issue」必然「有未删的父接收记录」。
        if (present(sourceInfoMapper.selectCount(new LambdaQueryWrapper<SourceInfo>()
                .eq(SourceInfo::getOwnerType, type.code())
                .eq(SourceInfo::getOwnerId, ownerId)
                .apply("deleted_at IS NULL")))) {
            return true;
        }
        // 资产的处置在 disposal_order（不属于记录表），因此资产主体不查这张表
        return type != RecordOwnerType.ASSET
                && present(disposalRecordMapper.selectCount(new LambdaQueryWrapper<DisposalRecord>()
                        .eq(DisposalRecord::getOwnerType, type.code())
                        .eq(DisposalRecord::getOwnerId, ownerId)
                        .apply("deleted_at IS NULL")));
    }

    private boolean present(Long count) {
        return count != null && count > 0;
    }
}

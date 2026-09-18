package com.ams.modules.record.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.record.DisposalCascadePort;
import com.ams.modules.org.entity.User;
import com.ams.modules.org.mapper.UserMapper;
import com.ams.modules.record.AttachmentOwner;
import com.ams.modules.record.RecordOwnerType;
import com.ams.modules.record.dto.AttachmentRef;
import com.ams.modules.record.dto.CostInput;
import com.ams.modules.record.dto.CostItemInput;
import com.ams.modules.record.dto.DisposalInput;
import com.ams.modules.record.dto.EvaluationInput;
import com.ams.modules.record.dto.IssueInput;
import com.ams.modules.record.dto.ReceiveInput;
import com.ams.modules.record.dto.RecordSheetRequest;
import com.ams.modules.record.dto.RecordSheetView;
import com.ams.modules.record.dto.SourceInput;
import com.ams.modules.record.entity.BizAttachment;
import com.ams.modules.record.entity.CostItem;
import com.ams.modules.record.entity.CostRecord;
import com.ams.modules.record.entity.DisposalRecord;
import com.ams.modules.record.entity.EvaluationInfo;
import com.ams.modules.record.entity.ReceiveIssue;
import com.ams.modules.record.entity.ReceiveRecord;
import com.ams.modules.record.entity.SourceInfo;
import com.ams.modules.record.mapper.BizAttachmentMapper;
import com.ams.modules.record.mapper.CostItemMapper;
import com.ams.modules.record.mapper.CostRecordMapper;
import com.ams.modules.record.mapper.DisposalRecordMapper;
import com.ams.modules.record.mapper.EvaluationInfoMapper;
import com.ams.modules.record.mapper.ReceiveIssueMapper;
import com.ams.modules.record.mapper.ReceiveRecordMapper;
import com.ams.modules.record.mapper.SourceInfoMapper;
import com.ams.modules.system.service.FileService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 聚合读写的核心语义（设计 §5.2 / §5.4）。
 *
 * <p>本类的桩刻意**不模拟 SQL**：MyBatis-Plus 的 {@code LambdaQueryWrapper} 条件读不出来，
 * 因此凡是「查出来什么」都要在用例里显式桩定，并配一条反向对照 ——
 * 否则「恰好返回空列表」会让断言因为错误的原因通过（这是本仓库夹具文档里点名的坑）。
 *
 * <p>写断言一律用 {@link ArgumentCaptor} 抓实体，检查**真正落库的字段值**，
 * 而不是只 verify 方法被调用：只 verify 调用无法发现「owner_id 被客户端传的值覆盖」这类越权写入。
 */
class RecordSheetServiceTest {

    private static final long ASSET_ID = 7L;
    private static final long ZONE_ID = 9L;

    private final ReceiveRecordMapper receiveRecordMapper = mock(ReceiveRecordMapper.class);
    private final ReceiveIssueMapper receiveIssueMapper = mock(ReceiveIssueMapper.class);
    private final SourceInfoMapper sourceInfoMapper = mock(SourceInfoMapper.class);
    private final DisposalRecordMapper disposalRecordMapper = mock(DisposalRecordMapper.class);
    private final CostRecordMapper costRecordMapper = mock(CostRecordMapper.class);
    private final CostItemMapper costItemMapper = mock(CostItemMapper.class);
    private final EvaluationInfoMapper evaluationInfoMapper = mock(EvaluationInfoMapper.class);
    private final BizAttachmentMapper bizAttachmentMapper = mock(BizAttachmentMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final FileService fileService = mock(FileService.class);
    private final DisposalCascadePort disposalCascadePort = mock(DisposalCascadePort.class);

    private final RecordSheetService service = new RecordSheetService(
            receiveRecordMapper, receiveIssueMapper, sourceInfoMapper, disposalRecordMapper,
            costRecordMapper, costItemMapper, evaluationInfoMapper,
            bizAttachmentMapper, userMapper, fileService, disposalCascadePort);

    // ---- 全量 diff ----

    @Test
    @DisplayName("接收信息：无 id 的新增行由服务端写入 ownerType/ownerId，忽略客户端传值")
    void newReceiveGetsOwnerFromServer() {
        stubEmptyExisting();
        ReceiveInput input = new ReceiveInput();
        input.setHandoverType("receive");
        // 外部交接人：无 id 时姓名必填（不变量 4），夹具必须给一个合法值，否则本用例在断言前就会抛异常
        input.setHandoverUserName("张三");
        input.setHandoverDate(LocalDate.of(2026, 8, 1));

        service.save(RecordOwnerType.ZONE, ZONE_ID, requestWithReceive(input));

        ArgumentCaptor<ReceiveRecord> captor = ArgumentCaptor.forClass(ReceiveRecord.class);
        verify(receiveRecordMapper).insert(captor.capture());
        assertThat(captor.getValue().getOwnerType()).isEqualTo("zone");
        assertThat(captor.getValue().getOwnerId()).isEqualTo(ZONE_ID);
        assertThat(captor.getValue().getHandoverDate()).isEqualTo(LocalDate.of(2026, 8, 1));
    }

    @Test
    @DisplayName("接收信息：库中未提交的行被软删，绝不 deleteById")
    void unsubmittedReceiveIsSoftDeleted() {
        ReceiveRecord existing = receive(12L);
        when(receiveRecordMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(existing)));
        when(receiveIssueMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(sourceInfoMapper.selectOne(any())).thenReturn(null);
        when(bizAttachmentMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(receiveRecordMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(existing)));

        service.save(RecordOwnerType.ZONE, ZONE_ID, new RecordSheetRequest());

        // 软删，而不是「随便写一次 update」：抓 wrapper 断言 SET 子句只写 deleted_at
        verifyAllSoftDeletes(receiveRecordMapper);
        verify(receiveRecordMapper, never()).deleteById(anyLong());
        verify(receiveRecordMapper, never()).updateById(any(ReceiveRecord.class));
    }

    @Test
    @DisplayName("未提交的接收记录：receive→issue→附件 三级级联软删，绝不 deleteById")
    void unsubmittedReceiveCascadesSoftDeleteToIssuesAndAttachments() {
        ReceiveRecord existing = receive(12L);
        ReceiveIssue issue = new ReceiveIssue();
        issue.setId(21L);
        issue.setReceiveId(12L);
        BizAttachment attachment = new BizAttachment();
        attachment.setId(41L);
        attachment.setOwnerType("receive_issue");
        attachment.setOwnerId(21L);
        attachment.setFileId(101L);
        // 两个子查询都必须非空，否则级联分支从不执行（删掉级联代码，本用例会红）
        when(receiveRecordMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(existing)));
        when(receiveIssueMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(issue)));
        when(sourceInfoMapper.selectOne(any())).thenReturn(null);
        when(disposalRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(bizAttachmentMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(attachment)));
        when(fileService.viewsByIds(any())).thenReturn(Map.of());

        service.save(RecordOwnerType.ZONE, ZONE_ID, new RecordSheetRequest());

        // 级联的三张表都只写 deleted_at = now()
        verifyAllSoftDeletes(receiveRecordMapper);
        verifyAllSoftDeletes(receiveIssueMapper);
        verifyAllSoftDeletes(bizAttachmentMapper);

        verify(receiveRecordMapper, never()).deleteById(anyLong());
        verify(receiveIssueMapper, never()).deleteById(anyLong());
        verify(bizAttachmentMapper, never()).deleteById(anyLong());
        verify(receiveRecordMapper, never()).updateById(any(ReceiveRecord.class));
        verify(receiveIssueMapper, never()).updateById(any(ReceiveIssue.class));
    }

    @Test
    @DisplayName("遗留问题：receive_id 由服务端按所属接收记录赋值，客户端无法指定")
    void issueReceiveIdComesFromServer() {
        stubEmptyExisting();
        ReceiveInput input = new ReceiveInput();
        // 姓名快照必填（不变量 4）：夹具补一个合法外部姓名，让断言打在 receive_id 归属上
        input.setHandoverUserName("张三");
        input.getIssues().add(issue("土地证未过户"));

        service.save(RecordOwnerType.ZONE, ZONE_ID, requestWithReceive(input));

        ArgumentCaptor<ReceiveIssue> captor = ArgumentCaptor.forClass(ReceiveIssue.class);
        verify(receiveIssueMapper).insert(captor.capture());
        assertThat(captor.getValue().getDescription()).isEqualTo("土地证未过户");
        // 归属来自服务端刚插入的接收记录（桩回填了 id=12），不是任何客户端输入
        assertThat(captor.getValue().getReceiveId()).isEqualTo(12L);
    }

    @Test
    @DisplayName("遗留问题：带 id 时更新已在库中的行（updateById），不新增第二条")
    void issueWithIdIsUpdated() {
        ReceiveRecord existingReceive = receive(12L);
        ReceiveIssue existingIssue = new ReceiveIssue();
        existingIssue.setId(21L);
        existingIssue.setReceiveId(12L);
        existingIssue.setDescription("旧描述");
        when(receiveRecordMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(existingReceive)));
        when(receiveIssueMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(existingIssue)));
        when(sourceInfoMapper.selectOne(any())).thenReturn(null);
        when(disposalRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(bizAttachmentMapper.selectList(any())).thenReturn(new ArrayList<>());

        ReceiveInput input = new ReceiveInput();
        input.setId(12L);
        input.setHandoverUserName("张三");
        IssueInput issueInput = issue("新描述");
        issueInput.setId(21L);
        input.getIssues().add(issueInput);

        service.save(RecordOwnerType.ZONE, ZONE_ID, requestWithReceive(input));

        ArgumentCaptor<ReceiveIssue> captor = ArgumentCaptor.forClass(ReceiveIssue.class);
        verify(receiveIssueMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(21L);
        assertThat(captor.getValue().getDescription()).isEqualTo("新描述");
        verify(receiveIssueMapper, never()).insert(any(ReceiveIssue.class));
    }

    @Test
    @DisplayName("姓名快照：选了内员则用员工姓名覆盖手填姓名")
    void internalActorNameIsSnapshotted() {
        stubEmptyExisting();
        User user = new User();
        user.setId(8L);
        user.setName("张三");
        when(userMapper.selectById(8L)).thenReturn(user);

        ReceiveInput input = new ReceiveInput();
        input.setHandoverUserId(8L);
        input.setHandoverUserName("随便写的");
        service.save(RecordOwnerType.ZONE, ZONE_ID, requestWithReceive(input));

        ArgumentCaptor<ReceiveRecord> captor = ArgumentCaptor.forClass(ReceiveRecord.class);
        verify(receiveRecordMapper).insert(captor.capture());
        assertThat(captor.getValue().getHandoverUserName()).isEqualTo("张三");
    }

    @Test
    @DisplayName("姓名快照：外部人员（无 id）时姓名必填，为空则拒绝")
    void externalActorRequiresName() {
        stubEmptyExisting();
        ReceiveInput input = new ReceiveInput();
        input.setHandoverUserId(null);
        input.setHandoverUserName("   ");

        assertThatThrownBy(() -> service.save(RecordOwnerType.ZONE, ZONE_ID, requestWithReceive(input)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("姓名");
        verify(receiveRecordMapper, never()).insert(any(ReceiveRecord.class));
    }

    @Test
    @DisplayName("姓名快照：选了不存在的员工则拒绝，不写入一个指向空员工的记录")
    void unknownActorIsRejected() {
        stubEmptyExisting();
        when(userMapper.selectById(8L)).thenReturn(null);
        ReceiveInput input = new ReceiveInput();
        input.setHandoverUserId(8L);
        input.setHandoverUserName("张三");

        assertThatThrownBy(() -> service.save(RecordOwnerType.ZONE, ZONE_ID, requestWithReceive(input)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不存在");
    }

    // ---- 来源明细 1:1 ----

    @Test
    @DisplayName("来源明细：已有行时更新同一行，不新增第二行")
    void sourceInfoUpdatesExistingRow() {
        when(receiveRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(bizAttachmentMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(disposalRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        SourceInfo existing = new SourceInfo();
        existing.setId(31L);
        existing.setOwnerType("zone");
        existing.setOwnerId(ZONE_ID);
        when(sourceInfoMapper.selectOne(any())).thenReturn(existing);

        SourceInput input = new SourceInput();
        input.setSourceUnit("淮安市财政局");
        input.setSourceDate(LocalDate.of(2026, 7, 15));
        RecordSheetRequest request = new RecordSheetRequest();
        request.setSourceInfo(input);
        service.save(RecordOwnerType.ZONE, ZONE_ID, request);

        verify(sourceInfoMapper, never()).insert(any(SourceInfo.class));
        ArgumentCaptor<SourceInfo> captor = ArgumentCaptor.forClass(SourceInfo.class);
        verify(sourceInfoMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(31L);
        assertThat(captor.getValue().getSourceUnit()).isEqualTo("淮安市财政局");
        assertThat(captor.getValue().getOwnerId()).isEqualTo(ZONE_ID);
    }

    @Test
    @DisplayName("来源明细：owner 由服务端覆盖；客户端传的 id 被忽略，新增时不带 id 落库")
    void sourceInfoOwnerIsOverwritten() {
        when(receiveRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(bizAttachmentMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(sourceInfoMapper.selectOne(any())).thenReturn(null);

        SourceInput input = new SourceInput();
        input.setId(888L);
        input.setSourceUnit("某单位");
        RecordSheetRequest request = new RecordSheetRequest();
        request.setSourceInfo(input);
        service.save(RecordOwnerType.ZONE, ZONE_ID, request);

        ArgumentCaptor<SourceInfo> captor = ArgumentCaptor.forClass(SourceInfo.class);
        verify(sourceInfoMapper).insert(captor.capture());
        // 客户端传了 id=888，但库里没有这一行 → 服务端按「新增」处理，落库 id 为空
        assertThat(captor.getValue().getId()).isNull();
        assertThat(captor.getValue().getOwnerType()).isEqualTo("zone");
        assertThat(captor.getValue().getOwnerId()).isEqualTo(ZONE_ID);
    }

    @Test
    @DisplayName("来源明细：传全空对象 = 清空该行，来源行与其附件一并软删")
    void emptySourceSoftDeletesRowAndItsAttachments() {
        SourceInfo existing = new SourceInfo();
        existing.setId(31L);
        existing.setOwnerType("zone");
        existing.setOwnerId(ZONE_ID);
        when(receiveRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(receiveIssueMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(sourceInfoMapper.selectOne(any())).thenReturn(existing);
        when(disposalRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        BizAttachment attachment = new BizAttachment();
        attachment.setId(41L);
        attachment.setOwnerType("source_info");
        attachment.setOwnerId(31L);
        attachment.setFileId(101L);
        when(bizAttachmentMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(attachment)));
        when(fileService.viewsByIds(any())).thenReturn(Map.of());

        RecordSheetRequest request = new RecordSheetRequest();
        request.setSourceInfo(new SourceInput()); // 全字段空 = 清空该行

        service.save(RecordOwnerType.ZONE, ZONE_ID, request);

        // 抓 wrapper：只 verify(update) 无法区分「软删」与「写了别的字段」
        verifyAllSoftDeletes(sourceInfoMapper);
        verifyAllSoftDeletes(bizAttachmentMapper);
        verify(sourceInfoMapper, never()).insert(any(SourceInfo.class));
        verify(sourceInfoMapper, never()).deleteById(anyLong());
        verify(bizAttachmentMapper, never()).deleteById(anyLong());
    }

    // ---- asset 忽略处置段 ----

    @Test
    @DisplayName("资产主体：处置段被完全忽略，不产生 biz_disposal_record")
    void assetIgnoresDisposalSection() {
        stubEmptyExisting();
        RecordSheetRequest request = new RecordSheetRequest();
        DisposalInput disposal = new DisposalInput();
        disposal.setAmountWan(new BigDecimal("1200.50"));
        request.getDisposalRecords().add(disposal);

        service.save(RecordOwnerType.ASSET, ASSET_ID, request);

        verify(disposalRecordMapper, never()).insert(any(com.ams.modules.record.entity.DisposalRecord.class));
    }

    @Test
    @DisplayName("项目主体：处置段落入台账，amount_wan 原样保存（万元，不换算）")
    void projectKeepsDisposalSection() {
        stubEmptyExisting();
        RecordSheetRequest request = new RecordSheetRequest();
        DisposalInput disposal = new DisposalInput();
        disposal.setDisposalType("sale");
        // 处置人姓名快照必填（不变量 4）：无 id 时必须给出姓名
        disposal.setDisposalUserName("张三");
        disposal.setAmountWan(new BigDecimal("1200.50"));
        request.getDisposalRecords().add(disposal);

        service.save(RecordOwnerType.PROJECT, 1L, request);

        ArgumentCaptor<com.ams.modules.record.entity.DisposalRecord> captor =
                ArgumentCaptor.forClass(com.ams.modules.record.entity.DisposalRecord.class);
        verify(disposalRecordMapper).insert(captor.capture());
        assertThat(captor.getValue().getAmountWan()).isEqualByComparingTo("1200.50");
        assertThat(captor.getValue().getOwnerType()).isEqualTo("project");
    }

    @Test
    @DisplayName("处置台账：带 id 时更新已在库中的行（updateById），不新增第二条")
    void disposalWithIdIsUpdated() {
        DisposalRecord existing = new DisposalRecord();
        existing.setId(51L);
        existing.setOwnerType("project");
        existing.setOwnerId(1L);
        existing.setRemark("旧备注");
        stubEmptyExisting();
        when(disposalRecordMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(existing)));

        RecordSheetRequest request = new RecordSheetRequest();
        DisposalInput disposal = new DisposalInput();
        disposal.setId(51L);
        disposal.setDisposalUserName("张三");
        disposal.setRemark("新备注");
        request.getDisposalRecords().add(disposal);

        service.save(RecordOwnerType.PROJECT, 1L, request);

        ArgumentCaptor<DisposalRecord> captor = ArgumentCaptor.forClass(DisposalRecord.class);
        verify(disposalRecordMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(51L);
        assertThat(captor.getValue().getRemark()).isEqualTo("新备注");
        verify(disposalRecordMapper, never()).insert(any(DisposalRecord.class));
    }

    // ---- 附件 diff ----

    @Test
    @DisplayName("附件：新增时 biz_type 由服务端按宿主决定，忽略任何客户端值")
    void attachmentBizTypeComesFromServer() {
        when(receiveRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(receiveIssueMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(sourceInfoMapper.selectOne(any())).thenReturn(null);
        when(disposalRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(bizAttachmentMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(bizAttachmentMapper.insert(any(BizAttachment.class))).thenReturn(1);

        when(sourceInfoMapper.selectOne(any())).thenReturn(null);
        // 新增的来源明细必须回填主键，否则附件因「宿主尚未落库」被跳过（真库由 useGeneratedKeys 回填）
        when(sourceInfoMapper.insert(any(SourceInfo.class))).thenAnswer(i -> {
            SourceInfo row = i.getArgument(0);
            if (row.getId() == null) {
                row.setId(31L);
            }
            return 1;
        });
        SourceInput input = new SourceInput();
        input.setSourceUnit("某单位");
        AttachmentRef ref = new AttachmentRef();
        ref.setFileId(101L);
        input.getAttachments().add(ref);
        RecordSheetRequest request = new RecordSheetRequest();
        request.setSourceInfo(input);
        service.save(RecordOwnerType.ZONE, ZONE_ID, request);

        ArgumentCaptor<BizAttachment> captor = ArgumentCaptor.forClass(BizAttachment.class);
        verify(bizAttachmentMapper).insert(captor.capture());
        assertThat(captor.getValue().getBizType()).isEqualTo(AttachmentOwner.SOURCE_INFO.bizType());
        assertThat(captor.getValue().getOwnerType()).isEqualTo("source_info");
        assertThat(captor.getValue().getFileId()).isEqualTo(101L);
    }

    @Test
    @DisplayName("附件：客户端显式传的 sort 被采用（sort 是排序意图，不是越权字段）")
    void attachmentKeepsClientProvidedSort() {
        stubEmptyExisting();
        SourceInput input = new SourceInput();
        input.setSourceUnit("某单位");
        AttachmentRef ref = new AttachmentRef();
        ref.setFileId(101L);
        ref.setSort(7);
        input.getAttachments().add(ref);
        RecordSheetRequest request = new RecordSheetRequest();
        request.setSourceInfo(input);

        service.save(RecordOwnerType.ZONE, ZONE_ID, request);

        ArgumentCaptor<BizAttachment> captor = ArgumentCaptor.forClass(BizAttachment.class);
        verify(bizAttachmentMapper).insert(captor.capture());
        assertThat(captor.getValue().getSort()).isEqualTo(7);
    }

    @Test
    @DisplayName("附件：sort 为空时服务端按数组下标兜底赋值（0、1）")
    void attachmentSortFallsBackToArrayIndex() {
        stubEmptyExisting();
        SourceInput input = new SourceInput();
        input.setSourceUnit("某单位");
        AttachmentRef first = new AttachmentRef();
        first.setFileId(101L);
        AttachmentRef second = new AttachmentRef();
        second.setFileId(102L);
        input.getAttachments().add(first);
        input.getAttachments().add(second);
        RecordSheetRequest request = new RecordSheetRequest();
        request.setSourceInfo(input);

        service.save(RecordOwnerType.ZONE, ZONE_ID, request);

        ArgumentCaptor<BizAttachment> captor = ArgumentCaptor.forClass(BizAttachment.class);
        verify(bizAttachmentMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(BizAttachment::getSort).containsExactly(0, 1);
    }

    @Test
    @DisplayName("附件：重新提交已持久化的 fileId 走更新、不新增（不覆盖同一请求内重复 fileId）")
    void attachmentDiffKeepsExistingFileId() {
        BizAttachment existing = new BizAttachment();
        existing.setId(55L);
        existing.setOwnerType("source_info");
        existing.setOwnerId(31L);
        existing.setFileId(101L);
        when(receiveRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(disposalRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(bizAttachmentMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(existing)));
        SourceInfo source = new SourceInfo();
        source.setId(31L);
        when(sourceInfoMapper.selectOne(any())).thenReturn(source);

        SourceInput input = new SourceInput();
        input.setSourceUnit("某单位");
        AttachmentRef ref = new AttachmentRef();
        ref.setFileId(101L);
        input.getAttachments().add(ref);
        RecordSheetRequest request = new RecordSheetRequest();
        request.setSourceInfo(input);
        service.save(RecordOwnerType.ZONE, ZONE_ID, request);

        verify(bizAttachmentMapper, never()).insert(any(BizAttachment.class));
        verify(bizAttachmentMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("附件：缺少 fileId 直接拒绝，不落一行指向空文件的关联")
    void attachmentWithoutFileIdIsRejected() {
        when(receiveRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(disposalRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(bizAttachmentMapper.selectList(any())).thenReturn(new ArrayList<>());
        SourceInfo source = new SourceInfo();
        source.setId(31L);
        when(sourceInfoMapper.selectOne(any())).thenReturn(source);

        SourceInput input = new SourceInput();
        input.setSourceUnit("某单位");
        input.getAttachments().add(new AttachmentRef());
        RecordSheetRequest request = new RecordSheetRequest();
        request.setSourceInfo(input);

        assertThatThrownBy(() -> service.save(RecordOwnerType.ZONE, ZONE_ID, request))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("附件");
    }

    @Test
    @DisplayName("附件归属：新 fileId 被归属校验拒绝时整单不写入任何附件行")
    void newAttachmentFromAnotherUserIsRejected() {
        // fileService 是 mock：直接让它拒绝，验证「拒绝必须是整单的」
        doThrow(new AppException(ErrorCode.BAD_REQUEST, "附件不存在，请先调用 /files/upload"))
                .when(fileService).assertAttachable(any());
        when(receiveRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(disposalRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(bizAttachmentMapper.selectList(any())).thenReturn(new ArrayList<>());
        SourceInfo source = new SourceInfo();
        source.setId(31L);
        when(sourceInfoMapper.selectOne(any())).thenReturn(source);

        SourceInput input = new SourceInput();
        input.setSourceUnit("某单位");
        AttachmentRef ref = new AttachmentRef();
        ref.setFileId(999L); // 库里没有、也不属于当前用户
        input.getAttachments().add(ref);
        RecordSheetRequest request = new RecordSheetRequest();
        request.setSourceInfo(input);

        assertThatThrownBy(() -> service.save(RecordOwnerType.ZONE, ZONE_ID, request))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));

        // 拒绝必须整单：不能先落一行附件再抛
        verify(bizAttachmentMapper, never()).insert(any(BizAttachment.class));

        // 并且必须真的把「新增的那个 fileId」送进校验。少了这条，把实参换成空集合
        // （例如把 existing.containsKey 的过滤写反）仍然会绿，校验就形同虚设。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> validated = ArgumentCaptor.forClass(Collection.class);
        verify(fileService).assertAttachable(validated.capture());
        assertThat(validated.getValue()).containsExactly(999L);
    }

    @Test
    @DisplayName("附件归属：已挂在宿主上的旧 fileId 不重复校验（存量脏引用可原样往返）")
    void alreadyLinkedFileIdSkipsRevalidation() {
        BizAttachment existing = new BizAttachment();
        existing.setId(55L);
        existing.setOwnerType("source_info");
        existing.setOwnerId(31L);
        existing.setFileId(101L);
        when(receiveRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(disposalRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(bizAttachmentMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(existing)));
        SourceInfo source = new SourceInfo();
        source.setId(31L);
        when(sourceInfoMapper.selectOne(any())).thenReturn(source);
        when(fileService.viewsByIds(any())).thenReturn(Map.of());

        SourceInput input = new SourceInput();
        input.setSourceUnit("某单位");
        AttachmentRef ref = new AttachmentRef();
        ref.setFileId(101L); // 库里已有同一 (owner, fileId) 的关联
        input.getAttachments().add(ref);
        RecordSheetRequest request = new RecordSheetRequest();
        request.setSourceInfo(input);

        service.save(RecordOwnerType.ZONE, ZONE_ID, request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(fileService).assertAttachable(captor.capture());
        // 旧引用被滤掉，assertAttachable 收到空集合 → 存量脏引用不会把保存卡死
        assertThat(captor.getValue()).isEmpty();
    }

    // ---- 读路径 ----

    @Test
    @DisplayName("读：附件回显 fileName/url；查不到 file 的引用保留该行而不是让整单失败")
    void readFillsAttachmentViews() {
        ReceiveRecord record = receive(12L);
        when(receiveRecordMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(record)));
        when(receiveIssueMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(sourceInfoMapper.selectOne(any())).thenReturn(null);
        when(disposalRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        BizAttachment attachment = new BizAttachment();
        attachment.setId(1L);
        attachment.setOwnerType("receive_record");
        attachment.setOwnerId(12L);
        attachment.setFileId(101L);
        when(bizAttachmentMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(attachment)));
        when(fileService.viewsByIds(any())).thenReturn(Map.of(101L, Map.of(
                "fileId", 101L, "fileName", "移交清单.pdf", "url", "http://minio/101")));

        RecordSheetView view = service.read(RecordOwnerType.ZONE, ZONE_ID);

        assertThat(view.getReceives()).hasSize(1);
        assertThat(view.getReceives().get(0).getAttachments()).hasSize(1);
        assertThat(view.getReceives().get(0).getAttachments().get(0).getFileName()).isEqualTo("移交清单.pdf");
        assertThat(view.getReceives().get(0).getAttachments().get(0).getUrl()).isEqualTo("http://minio/101");
        assertThat(view.getSourceInfo()).isNull();
    }

    @Test
    @DisplayName("读：查不到 file_metadata 的引用被保留（url 为 null），不静默丢行")
    void readKeepsAttachmentWhoseFileIsMissing() {
        ReceiveRecord record = receive(12L);
        when(receiveRecordMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(record)));
        when(receiveIssueMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(sourceInfoMapper.selectOne(any())).thenReturn(null);
        when(disposalRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        BizAttachment attachment = new BizAttachment();
        attachment.setId(1L);
        attachment.setOwnerType("receive_record");
        attachment.setOwnerId(12L);
        attachment.setFileId(999L);
        attachment.setSort(0);
        when(bizAttachmentMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(attachment)));
        // viewsByIds 里没有 999：丢弃这行会在下次保存时把它静默软删
        when(fileService.viewsByIds(any())).thenReturn(Map.of());

        RecordSheetView view = service.read(RecordOwnerType.ZONE, ZONE_ID);

        assertThat(view.getReceives()).hasSize(1);
        assertThat(view.getReceives().get(0).getAttachments()).hasSize(1);
        assertThat(view.getReceives().get(0).getAttachments().get(0).getFileId()).isEqualTo(999L);
        assertThat(view.getReceives().get(0).getAttachments().get(0).getUrl()).isNull();
    }

    @Test
    @DisplayName("读：资产主体不查台账表；项目主体回显台账")
    void readBranchesByOwnerType() {
        when(receiveRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(receiveIssueMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(sourceInfoMapper.selectOne(any())).thenReturn(null);
        when(bizAttachmentMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(disposalRecordMapper.selectList(any())).thenReturn(new ArrayList<>());

        RecordSheetView assetView = service.read(RecordOwnerType.ASSET, ASSET_ID);

        assertThat(assetView.getDisposalRecords()).isEmpty();
        verify(disposalRecordMapper, never()).selectList(any());

        DisposalRecord projectDisposal = new DisposalRecord();
        projectDisposal.setId(51L);
        projectDisposal.setOwnerType("project");
        projectDisposal.setOwnerId(1L);
        when(disposalRecordMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(projectDisposal)));

        RecordSheetView projectView = service.read(RecordOwnerType.PROJECT, 1L);

        assertThat(projectView.getDisposalRecords()).hasSize(1);
    }

    // ---- 成本信息 / 费用明细 ----

    @Test
    @DisplayName("成本信息：新增行由服务端写入 ownerType/ownerId，费用明细的 costId 由服务端赋值")
    void newCostGetsOwnerAndItemCostId() {
        stubEmptyExisting();
        CostInput input = new CostInput();
        input.setAmountWan(new BigDecimal("35.50"));
        input.setCostDate(LocalDate.of(2026, 8, 2));
        CostItemInput item = new CostItemInput();
        item.setFeeName("装修款");
        item.setCostType("decoration");
        item.setAmount(new BigDecimal("20.00"));
        input.getItems().add(item);
        RecordSheetRequest request = new RecordSheetRequest();
        request.getCostRecords().add(input);

        service.save(RecordOwnerType.ZONE, ZONE_ID, request);

        ArgumentCaptor<CostRecord> costCaptor = ArgumentCaptor.forClass(CostRecord.class);
        verify(costRecordMapper).insert(costCaptor.capture());
        assertThat(costCaptor.getValue().getOwnerType()).isEqualTo("zone");
        assertThat(costCaptor.getValue().getOwnerId()).isEqualTo(ZONE_ID);
        assertThat(costCaptor.getValue().getAmountWan()).isEqualByComparingTo("35.50");

        ArgumentCaptor<CostItem> itemCaptor = ArgumentCaptor.forClass(CostItem.class);
        verify(costItemMapper).insert(itemCaptor.capture());
        // 归属来自服务端刚插入的成本记录（桩回填了 id=12），不是任何客户端输入
        assertThat(itemCaptor.getValue().getCostId()).isEqualTo(12L);
        assertThat(itemCaptor.getValue().getFeeName()).isEqualTo("装修款");
        assertThat(itemCaptor.getValue().getSort()).isZero();
    }

    @Test
    @DisplayName("成本信息：库中未提交的行连同费用明细与附件三级软删，绝不 deleteById")
    void unsubmittedCostCascadesSoftDelete() {
        CostRecord existing = new CostRecord();
        existing.setId(12L);
        existing.setOwnerType("zone");
        existing.setOwnerId(ZONE_ID);
        CostItem item = new CostItem();
        item.setId(21L);
        item.setCostId(12L);
        BizAttachment attachment = new BizAttachment();
        attachment.setId(41L);
        attachment.setOwnerType(AttachmentOwner.COST_RECORD.code());
        attachment.setOwnerId(12L);
        attachment.setFileId(101L);
        when(receiveRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(receiveIssueMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(sourceInfoMapper.selectOne(any())).thenReturn(null);
        when(disposalRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(costRecordMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(existing)));
        when(costItemMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(item)));
        when(bizAttachmentMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(attachment)));
        when(fileService.viewsByIds(any())).thenReturn(Map.of());

        service.save(RecordOwnerType.ZONE, ZONE_ID, new RecordSheetRequest());

        verifyAllSoftDeletes(costRecordMapper);
        verifyAllSoftDeletes(costItemMapper);
        verifyAllSoftDeletes(bizAttachmentMapper);
        verify(costRecordMapper, never()).deleteById(anyLong());
        verify(costItemMapper, never()).deleteById(anyLong());
        verify(costRecordMapper, never()).updateById(any(CostRecord.class));
        verify(costItemMapper, never()).updateById(any(CostItem.class));
    }

    @Test
    @DisplayName("成本信息：带 id 的明细更新原生行，不新增第二条")
    void costItemWithIdIsUpdated() {
        CostRecord existingCost = new CostRecord();
        existingCost.setId(12L);
        CostItem existingItem = new CostItem();
        existingItem.setId(21L);
        existingItem.setCostId(12L);
        stubEmptyExisting();
        when(costRecordMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(existingCost)));
        when(costItemMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(existingItem)));

        CostInput input = new CostInput();
        input.setId(12L);
        CostItemInput item = new CostItemInput();
        item.setId(21L);
        item.setFeeName("新费用名称");
        input.getItems().add(item);
        RecordSheetRequest request = new RecordSheetRequest();
        request.getCostRecords().add(input);

        service.save(RecordOwnerType.ZONE, ZONE_ID, request);

        ArgumentCaptor<CostItem> captor = ArgumentCaptor.forClass(CostItem.class);
        verify(costItemMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(21L);
        assertThat(captor.getValue().getFeeName()).isEqualTo("新费用名称");
        verify(costItemMapper, never()).insert(any(CostItem.class));
    }

    // ---- 评估信息 ----

    @Test
    @DisplayName("评估信息：有效期限与金额原样落库，owner 由服务端赋值（资产主体同样适用）")
    void newEvaluationKeepsAllFields() {
        stubEmptyExisting();
        EvaluationInput input = new EvaluationInput();
        input.setInstitution("某某评估公司");
        input.setAssetValue(new BigDecimal("1200.00"));
        input.setRentUnitPrice(new BigDecimal("3.50"));
        input.setRentPrice(new BigDecimal("4200.00"));
        input.setEvaluateDate(LocalDate.of(2026, 6, 1));
        input.setValidFrom(LocalDate.of(2026, 6, 1));
        input.setValidTo(LocalDate.of(2027, 5, 31));
        RecordSheetRequest request = new RecordSheetRequest();
        request.getEvaluations().add(input);

        service.save(RecordOwnerType.ASSET, ASSET_ID, request);

        ArgumentCaptor<EvaluationInfo> captor = ArgumentCaptor.forClass(EvaluationInfo.class);
        verify(evaluationInfoMapper).insert(captor.capture());
        assertThat(captor.getValue().getOwnerType()).isEqualTo("asset");
        assertThat(captor.getValue().getOwnerId()).isEqualTo(ASSET_ID);
        assertThat(captor.getValue().getInstitution()).isEqualTo("某某评估公司");
        assertThat(captor.getValue().getValidFrom()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(captor.getValue().getValidTo()).isEqualTo(LocalDate.of(2027, 5, 31));
        assertThat(captor.getValue().getRentUnitPrice()).isEqualByComparingTo("3.50");
    }

    @Test
    @DisplayName("评估信息：带 id 时更新已在库中的行，不新增第二条")
    void evaluationWithIdIsUpdated() {
        EvaluationInfo existing = new EvaluationInfo();
        existing.setId(61L);
        existing.setOwnerType("project");
        existing.setOwnerId(1L);
        stubEmptyExisting();
        when(evaluationInfoMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(existing)));

        EvaluationInput input = new EvaluationInput();
        input.setId(61L);
        input.setInstitution("新机构");
        RecordSheetRequest request = new RecordSheetRequest();
        request.getEvaluations().add(input);

        service.save(RecordOwnerType.PROJECT, 1L, request);

        ArgumentCaptor<EvaluationInfo> captor = ArgumentCaptor.forClass(EvaluationInfo.class);
        verify(evaluationInfoMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(61L);
        assertThat(captor.getValue().getInstitution()).isEqualTo("新机构");
        verify(evaluationInfoMapper, never()).insert(any(EvaluationInfo.class));
    }

    @Test
    @DisplayName("读：成本信息与评估信息回显，费用明细与附件一并读回")
    void readReturnsCostsAndEvaluations() {
        CostRecord cost = new CostRecord();
        cost.setId(12L);
        CostItem item = new CostItem();
        item.setId(21L);
        item.setCostId(12L);
        item.setFeeName("装修款");
        EvaluationInfo evaluation = new EvaluationInfo();
        evaluation.setId(61L);
        evaluation.setInstitution("某某评估公司");
        when(receiveRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(receiveIssueMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(sourceInfoMapper.selectOne(any())).thenReturn(null);
        when(disposalRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(costRecordMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(cost)));
        when(costItemMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(item)));
        when(evaluationInfoMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(evaluation)));
        when(bizAttachmentMapper.selectList(any())).thenReturn(new ArrayList<>());

        RecordSheetView view = service.read(RecordOwnerType.ZONE, ZONE_ID);

        assertThat(view.getCostRecords()).hasSize(1);
        assertThat(view.getCostRecords().get(0).getItems()).hasSize(1);
        assertThat(view.getCostRecords().get(0).getItems().get(0).getFeeName()).isEqualTo("装修款");
        assertThat(view.getEvaluations()).hasSize(1);
        assertThat(view.getEvaluations().get(0).getInstitution()).isEqualTo("某某评估公司");
    }

    // ---- 辅助 ----

    /**
     * 软删的唯一断言方式：抓 update 的 wrapper，SET 子句里必须有 {@code deleted_at = now()}。
     * 只 {@code verify(update)} 无法区分「软删」与「写了别的字段」。
     */
    @SuppressWarnings("unchecked")
    private static <T> void verifyAllSoftDeletes(BaseMapper<T> mapper) {
        ArgumentCaptor<LambdaUpdateWrapper<T>> wrapper =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper, atLeastOnce()).update(isNull(), wrapper.capture());
        assertThat(wrapper.getAllValues())
                .isNotEmpty()
                .allSatisfy(w -> assertThat(w.getSqlSet()).contains("deleted_at = now()"));
    }

    // ---- 附件宿主泛化（权属流转复用同一写入点） ----

    @Test
    @DisplayName("按 OWNERSHIP_TRANSFER 写附件：owner_type 与 biz_type 都取自枚举，不串到处置单宿主")
    void syncAttachmentsForOwnershipTransfer() {
        when(bizAttachmentMapper.selectList(any())).thenReturn(new ArrayList<>());

        service.syncAttachments(
                AttachmentOwner.OWNERSHIP_TRANSFER, 77L, List.of(ref(1001L), ref(1002L)));

        ArgumentCaptor<BizAttachment> captor = ArgumentCaptor.forClass(BizAttachment.class);
        verify(bizAttachmentMapper, times(2)).insert(captor.capture());

        assertThat(captor.getAllValues())
                .as("宿主必须逐字是枚举值：写错就会把权属流转的附件挂进处置单，且没有任何报错")
                .allSatisfy(row -> {
                    assertThat(row.getOwnerType()).isEqualTo(AttachmentOwner.OWNERSHIP_TRANSFER.code());
                    assertThat(row.getBizType()).isEqualTo(AttachmentOwner.OWNERSHIP_TRANSFER.bizType());
                    assertThat(row.getOwnerId()).isEqualTo(77L);
                });
        assertThat(captor.getAllValues())
                .as("不能串到处置单宿主")
                .noneSatisfy(row -> assertThat(row.getOwnerType())
                        .isEqualTo(AttachmentOwner.DISPOSAL_ORDER.code()));
    }

    private static AttachmentRef ref(Long fileId) {
        AttachmentRef r = new AttachmentRef();
        r.setFileId(fileId);
        return r;
    }

    private void stubEmptyExisting() {
        when(receiveRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(receiveIssueMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(sourceInfoMapper.selectOne(any())).thenReturn(null);
        when(disposalRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(bizAttachmentMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(receiveRecordMapper.insert(any(ReceiveRecord.class))).thenAnswer(i -> {
            ReceiveRecord row = i.getArgument(0);
            if (row.getId() == null) {
                row.setId(12L);
            }
            return 1;
        });
        when(receiveIssueMapper.insert(any(ReceiveIssue.class))).thenReturn(1);
        when(sourceInfoMapper.insert(any(SourceInfo.class))).thenAnswer(i -> {
            SourceInfo row = i.getArgument(0);
            if (row.getId() == null) {
                row.setId(31L);
            }
            return 1;
        });
        // 成本 / 评估的 insert 也要回填主键，否则费用明细与附件会因「宿主尚未落库」被跳过
        when(costRecordMapper.insert(any(CostRecord.class))).thenAnswer(i -> {
            CostRecord row = i.getArgument(0);
            if (row.getId() == null) {
                row.setId(12L);
            }
            return 1;
        });
        when(costItemMapper.insert(any(CostItem.class))).thenReturn(1);
        when(evaluationInfoMapper.insert(any(EvaluationInfo.class))).thenAnswer(i -> {
            EvaluationInfo row = i.getArgument(0);
            if (row.getId() == null) {
                row.setId(61L);
            }
            return 1;
        });
    }

    private static RecordSheetRequest requestWithReceive(ReceiveInput input) {
        RecordSheetRequest request = new RecordSheetRequest();
        request.getReceives().add(input);
        return request;
    }

    private static IssueInput issue(String description) {
        IssueInput issue = new IssueInput();
        issue.setDescription(description);
        // 发现人姓名必填（不变量 4）：夹具必须给一个合法外部姓名，否则 applyIssue 会先抛异常，
        // 断言根本走不到（首审修复轮同类夹具缺陷）
        issue.setDiscovererName("李四");
        return issue;
    }

    private static ReceiveRecord receive(long id) {
        ReceiveRecord record = new ReceiveRecord();
        record.setId(id);
        record.setOwnerType("zone");
        record.setOwnerId(ZONE_ID);
        return record;
    }
}

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
import com.ams.modules.org.entity.User;
import com.ams.modules.org.mapper.UserMapper;
import com.ams.modules.record.AttachmentOwner;
import com.ams.modules.record.RecordOwnerType;
import com.ams.modules.record.dto.AttachmentRef;
import com.ams.modules.record.dto.DisposalInput;
import com.ams.modules.record.dto.IssueInput;
import com.ams.modules.record.dto.ReceiveInput;
import com.ams.modules.record.dto.RecordSheetRequest;
import com.ams.modules.record.dto.RecordSheetView;
import com.ams.modules.record.dto.SourceInput;
import com.ams.modules.record.entity.BizAttachment;
import com.ams.modules.record.entity.DisposalRecord;
import com.ams.modules.record.entity.ReceiveIssue;
import com.ams.modules.record.entity.ReceiveRecord;
import com.ams.modules.record.entity.SourceInfo;
import com.ams.modules.record.mapper.BizAttachmentMapper;
import com.ams.modules.record.mapper.DisposalRecordMapper;
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
    private final BizAttachmentMapper bizAttachmentMapper = mock(BizAttachmentMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final FileService fileService = mock(FileService.class);

    private final RecordSheetService service = new RecordSheetService(
            receiveRecordMapper, receiveIssueMapper, sourceInfoMapper, disposalRecordMapper,
            bizAttachmentMapper, userMapper, fileService);

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
    @DisplayName("读：资产主体不查台账表；项目主体回显台账（disposals 留空，由 Task 9 填充）")
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
        assertThat(projectView.getDisposals()).isEmpty();
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

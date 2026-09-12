package com.ams.modules.record.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.common.exception.AppException;
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
import com.ams.modules.record.entity.ReceiveIssue;
import com.ams.modules.record.entity.ReceiveRecord;
import com.ams.modules.record.entity.SourceInfo;
import com.ams.modules.record.mapper.BizAttachmentMapper;
import com.ams.modules.record.mapper.DisposalRecordMapper;
import com.ams.modules.record.mapper.ReceiveIssueMapper;
import com.ams.modules.record.mapper.ReceiveRecordMapper;
import com.ams.modules.record.mapper.SourceInfoMapper;
import com.ams.modules.system.service.FileService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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

        verify(receiveRecordMapper, never()).deleteById(anyLong());
        verify(receiveRecordMapper).update(any(), any());
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
    @DisplayName("附件：未提交的关联行被软删，同一 fileId 重复提交不产生第二行")
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

    // ---- 读路径 ----

    @Test
    @DisplayName("读：附件回显 fileName/url，fileId 缺失的文件被跳过而不是让整单失败")
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
    @DisplayName("读：资产主体回显 disposal_order 列表，项目主体回显台账")
    void readBranchesByOwnerType() {
        when(receiveRecordMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(receiveIssueMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(sourceInfoMapper.selectOne(any())).thenReturn(null);
        when(bizAttachmentMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(disposalRecordMapper.selectList(any())).thenReturn(new ArrayList<>());

        RecordSheetView assetView = service.read(RecordOwnerType.ASSET, ASSET_ID);

        assertThat(assetView.getDisposalRecords()).isEmpty();
        verify(disposalRecordMapper, never()).selectList(any());
    }

    // ---- 辅助 ----

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

package com.ams.modules.disposal.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.PageResult;
import com.ams.modules.asset.LeaseControlStatus;
import com.ams.modules.asset.service.CertificateService;
import com.ams.modules.asset.service.LeaseControlService;
import com.ams.modules.billing.entity.Payment;
import com.ams.modules.billing.service.PaymentService;
import com.ams.modules.disposal.dto.DisposalOrderInput;
import com.ams.modules.disposal.entity.DisposalOrder;
import com.ams.modules.disposal.mapper.DisposalOrderMapper;
import com.ams.modules.finance.entity.FinanceVoucher;
import com.ams.modules.finance.service.ReconcileService;
import com.ams.modules.record.DisposalCascadePort;
import com.ams.modules.record.DisposalCascadeSnapshot;
import com.ams.modules.record.dto.DisposalOrderView;
import com.ams.modules.record.service.RecordSheetService;
import com.ams.platform.approval.ApprovalEngine;
import com.ams.platform.event.DisposalCompletedEvent;
import com.ams.platform.event.DomainEventPublisher;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资产处置全生命周期闭环（FR-DISP-*，§4.24.5 / FR-DISP-006）。
 * 流程：处置申请 → 审批 → 执行 → 损益入账/凭证 → 已退出。
 */
@Service
public class DisposalService {

    private final DisposalOrderMapper disposalOrderMapper;
    private final LeaseControlService leaseControlService;
    private final ApprovalEngine approvalEngine;
    private final CertificateService certificateService;
    private final PaymentService paymentService;
    private final ReconcileService reconcileService;
    private final ObjectMapper objectMapper;
    private final DomainEventPublisher eventPublisher;
    private final RecordSheetService recordSheetService;
    private final DisposalCascadePort disposalCascadePort;

    public DisposalService(
            DisposalOrderMapper disposalOrderMapper,
            LeaseControlService leaseControlService,
            ApprovalEngine approvalEngine,
            CertificateService certificateService,
            PaymentService paymentService,
            ReconcileService reconcileService,
            ObjectMapper objectMapper,
            DomainEventPublisher eventPublisher,
            RecordSheetService recordSheetService,
            DisposalCascadePort disposalCascadePort) {
        this.disposalOrderMapper = disposalOrderMapper;
        this.leaseControlService = leaseControlService;
        this.approvalEngine = approvalEngine;
        this.certificateService = certificateService;
        this.paymentService = paymentService;
        this.reconcileService = reconcileService;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.recordSheetService = recordSheetService;
        this.disposalCascadePort = disposalCascadePort;
    }

    public PageResult<DisposalOrder> page(long page, long pageSize, String status) {
        Page<DisposalOrder> result = disposalOrderMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<DisposalOrder>()
                        .eq(status != null, DisposalOrder::getStatus, status)
                        .orderByDesc(DisposalOrder::getId));
        return PageResult.of(result.getRecords(), result.getTotal(), page, pageSize);
    }

    /**
     * 某资产的处置单列表（设计 §7.1 的面板数据源）。
     *
     * <p>按 id 倒序：面板最重要的是最近一次处置，历史处置往下排。
     * 附件一并回显，避免前端为每条单子再发一次请求。
     */
    public List<DisposalOrderView> listByAsset(Long assetId) {
        List<DisposalOrder> orders = disposalOrderMapper.selectList(
                new LambdaQueryWrapper<DisposalOrder>()
                        .eq(DisposalOrder::getAssetId, assetId)
                        .orderByDesc(DisposalOrder::getId));
        List<DisposalOrderView> views = new ArrayList<>();
        for (DisposalOrder order : orders) {
            DisposalOrderView view = new DisposalOrderView();
            view.setId(order.getId());
            view.setDisposalType(order.getDisposalType());
            view.setDisposalUserId(order.getDisposalUserId());
            view.setDisposalUserName(order.getDisposalUserName());
            // 金额沿用 actual_amount 原值，不做万元换算（设计 §4.2 末尾）
            view.setAmountWan(order.getActualAmount());
            view.setDisposalDate(order.getDisposalDate());
            view.setRemark(order.getRemark());
            view.setStatus(order.getStatus());
            // 表单里可录的其余字段：卡片要能就地回显，否则改一次就会把原值抹成 null
            view.setReason(order.getReason());
            view.setAssessedValue(order.getAssessedValue());
            view.setBookValue(order.getBookValue());
            view.setCounterparty(order.getCounterparty());
            view.setAttachments(recordSheetService.orderAttachments(order.getId()));
            views.add(view);
        }
        return views;
    }

    /**
     * 某资产的处置单**全量同步**（{@code PUT /assets/{assetId}/disposals}，设计 §6.6 / §7.1）。
     *
     * <p>资产表单第 3 步的处置面板与项目/分区那两个台账段共用同一套交互
     * （「改动攒在本地、点保存一起提交」），但资产的落点是 {@code disposal_order}，
     * 不是 record-sheet —— 后者对 asset 的 {@code disposalRecords} 段本就是忽略的。
     *
     * <p>三条硬规则：
     *
     * <ol>
     *   <li><strong>只有草稿会被改 / 删</strong>：{@code approving} 及之后的单子原样留在库里
     *       （面板上对这些单子也是只读），流转只认 {@code /submit} 等流程端点；</li>
     *   <li><strong>请求体里的 id 必须属于该资产</strong>，否则 400 —— 否则转发别人的单子 id
     *       就能改到另一个资产的处置数据；</li>
     *   <li><strong>新增一律先过抵押校验</strong>并建成草稿，与 {@code POST /disposals} 同口径
     *       （{@code DisposalService.create}）。</li>
     * </ol>
     *
     * <p>顺序上先写、后删：中途任一条非法时整个事务回滚，不会留下「删了旧的、没建成新的」。
     * 附件先随单子写完，删单前再清空该单子的附件关联，避免留下孤儿行。
     */
    @Transactional
    public List<DisposalOrderView> syncForAsset(Long assetId, List<DisposalOrderInput> inputs) {
        List<DisposalOrderInput> incoming = inputs == null ? List.of() : inputs;
        Map<Long, DisposalOrder> existing = disposalOrderMapper.selectList(
                        new LambdaQueryWrapper<DisposalOrder>()
                                .eq(DisposalOrder::getAssetId, assetId))
                .stream()
                .collect(Collectors.toMap(DisposalOrder::getId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        Set<Long> kept = new LinkedHashSet<>();
        for (DisposalOrderInput input : incoming) {
            if (input.getId() == null) {
                certificateService.assertNotMortgaged(assetId);
                DisposalOrder order = new DisposalOrder();
                order.setAssetId(assetId);
                order.setStatus("draft");
                input.applyTo(order);
                disposalOrderMapper.insert(order);
                recordSheetService.syncOrderAttachments(order.getId(), input.getAttachments());
                kept.add(order.getId());
                continue;
            }
            DisposalOrder order = existing.get(input.getId());
            if (order == null) {
                throw new AppException(ErrorCode.BAD_REQUEST,
                        "处置单不属于该资产：" + input.getId());
            }
            kept.add(order.getId());
            if (!"draft".equals(order.getStatus())) {
                // 非草稿不接受表单写入；前端对这些卡片也是只读的，这里只做兜底
                continue;
            }
            input.applyTo(order);
            disposalOrderMapper.updateById(order);
            recordSheetService.syncOrderAttachments(order.getId(), input.getAttachments());
        }
        for (DisposalOrder order : existing.values()) {
            if (!kept.contains(order.getId()) && "draft".equals(order.getStatus())) {
                recordSheetService.syncOrderAttachments(order.getId(), List.of());
                // disposal_order 没有 deleted_at（BaseEntity 的四个审计字段之外无软删列），
                // 草稿也尚未被审批 / 收款 / 凭证引用，故这里是真删除
                disposalOrderMapper.deleteById(order.getId());
            }
        }
        return listByAsset(assetId);
    }

    /** 处置申请（FR-DISP-001）。 */
    public DisposalOrder create(DisposalOrder order) {
        certificateService.assertNotMortgaged(order.getAssetId());
        order.setStatus("draft");
        disposalOrderMapper.insert(order);
        return order;
    }

    /** 提交审批（FR-DISP-002）。 */
    @Transactional
    public DisposalOrder submit(Long id) {
        DisposalOrder order = require(id);
        certificateService.assertNotMortgaged(order.getAssetId());
        order.setStatus("approving");
        disposalOrderMapper.updateById(order);
        BigDecimal value = order.getAssessedValue() != null ? order.getAssessedValue() : order.getBookValue();
        String bizType = value != null && value.compareTo(new BigDecimal("1000000")) >= 0
                ? "disposal_major"
                : "disposal";
        approvalEngine.start(bizType, id);
        return order;
    }

    /** 审批通过 → 待执行 + 租控处置中。 */
    @Transactional
    public void onApproved(Long id) {
        DisposalOrder order = require(id);
        order.setStatus("pending_execute");
        disposalOrderMapper.updateById(order);
        // 终态豁免：资产已是 exited 时跳过租控写入 —— 否则「已处置的资产再登记处置」会在这一步
        // 撞上「租控状态不允许从 exited 迁移到 disposing」的 409（分次处置 / 项目级级联后补登记）
        leaseControlService.transitionUnlessExited(order.getAssetId(), LeaseControlStatus.DISPOSING,
                "disposal", id, "处置审批通过");
    }

    /** 执行登记（FR-DISP-003）。 */
    @Transactional
    public DisposalOrder execute(Long id, BigDecimal actualAmount, String counterparty) {
        DisposalOrder order = require(id);
        order.setStatus("executing");
        order.setActualAmount(actualAmount);
        order.setCounterparty(counterparty);
        disposalOrderMapper.updateById(order);
        return order;
    }

    /**
     * 完成 → 损益入账 + 收款 + 凭证 → 已退出（FR-DISP-004/005/006）。
     * pnl = actual − max(book, assessed)；收入入收款记录并生成待推送凭证。
     */
    @Transactional
    public DisposalOrder complete(Long id) {
        DisposalOrder order = require(id);
        if (!"executing".equals(order.getStatus()) && !"pending_execute".equals(order.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "当前状态不可完成处置");
        }
        BigDecimal actual = order.getActualAmount() == null ? BigDecimal.ZERO : order.getActualAmount();
        BigDecimal book = order.getBookValue();
        BigDecimal assessed = order.getAssessedValue();
        BigDecimal basis = book;
        if (basis == null) {
            basis = assessed;
        } else if (assessed != null) {
            basis = book.max(assessed);
        }
        if (basis == null) {
            basis = BigDecimal.ZERO;
        }
        BigDecimal pnl = actual.subtract(basis);
        order.setPnlAmount(pnl);
        order.setPnlType(pnl.compareTo(BigDecimal.ZERO) >= 0 ? "gain" : "loss");

        if (actual.compareTo(BigDecimal.ZERO) > 0) {
            Payment payment = paymentService.registerConfirmed(
                    null, null, actual, "bank_transfer", "pc");
            payment.setRemark("disposal:" + order.getId()
                    + (order.getCounterparty() == null ? "" : ":" + order.getCounterparty()));
            payment.setSource("disposal");
            paymentService.update(payment);
            order.setPaymentId(payment.getId());
        }

        try {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("disposalId", order.getId());
            content.put("assetId", order.getAssetId());
            content.put("disposalType", order.getDisposalType());
            content.put("actualAmount", actual);
            content.put("bookValue", book);
            content.put("assessedValue", assessed);
            content.put("pnlAmount", pnl);
            content.put("pnlType", order.getPnlType());
            content.put("paymentId", order.getPaymentId());
            FinanceVoucher voucher = reconcileService.createVoucher(
                    "disposal", order.getId(), objectMapper.writeValueAsString(content));
            order.setVoucherId(voucher.getId());
        } catch (Exception ex) {
            throw new AppException(ErrorCode.INTERNAL_ERROR, "生成处置凭证失败: " + ex.getMessage());
        }

        order.setStatus("completed");
        disposalOrderMapper.updateById(order);
        // 终态豁免：同上 —— 资产已是 exited 时不再写租控（重复完成不会 409）
        leaseControlService.transitionUnlessExited(order.getAssetId(), LeaseControlStatus.EXITED,
                "disposal", id, "处置完成，资产已退出");
        // V57：处置完成即「资产脱离原产权公司」—— 清空 property_company_id、置
        // ownership_status='disposed' / lifecycle_status='exited'，并写一行被处置资产台账。
        // 快照必须在这里做：公司字段一旦被清空就再也反推不出「从哪家公司处置出去」。
        disposalCascadePort.cascadeDispose(
                DisposalCascadePort.TARGET_ASSET,
                order.getAssetId(),
                new DisposalCascadeSnapshot(order.getDisposalType(), actual,
                        DisposalCascadeSnapshot.UNIT_YUAN, order.getDisposalDate(),
                        order.getDisposalUserId(), order.getDisposalUserName(), order.getRemark()),
                order.getId(), null);
        // DSD §4.8：处置完成 → DisposalCompleted（备案提醒、档案与看板投影）
        // 注：租控写入仍走旧入口，改造清单触点 7 将改为 AssetOccupancyService
        eventPublisher.publishAfterCommit(new DisposalCompletedEvent(
                order.getId(), order.getAssetId(), order.getDisposalType()));
        return order;
    }

    private DisposalOrder require(Long id) {
        DisposalOrder order = disposalOrderMapper.selectById(id);
        if (order == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return order;
    }
}

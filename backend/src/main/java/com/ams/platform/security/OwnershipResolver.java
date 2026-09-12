package com.ams.platform.security;

import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.Project;
import com.ams.modules.asset.entity.ProjectZone;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.ProjectMapper;
import com.ams.modules.asset.mapper.ProjectZoneMapper;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.entity.Payment;
import com.ams.modules.billing.entity.RefundOrder;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.billing.mapper.PaymentMapper;
import com.ams.modules.billing.mapper.RefundOrderMapper;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.entity.VacateOrder;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.modules.contract.mapper.VacateOrderMapper;
import com.ams.modules.invoice.entity.Invoice;
import com.ams.modules.invoice.mapper.InvoiceMapper;
import org.springframework.stereotype.Service;

/**
 * 数据归属推导（设计 6.1 第 3 条）：沿引用链把「没有公司列」的实体归到某个公司。
 *
 * <p>合同、账单、收款、发票这些表<strong>本身没有公司列</strong>，只能沿外键回溯到资产：
 * <pre>
 *   asset                          -> operating_company_id
 *   project                        -> company_id
 *   contract                       -> asset
 *   bill                           -> asset（有 asset_id 时直接用，否则 contract -> asset）
 *   payment                        -> contract -> asset
 *   invoice                        -> bill / payment -> ... -> asset
 * </pre>
 *
 * <p><strong>资产上同时有经营公司与产权公司，本设计统一取经营公司</strong>
 * （{@link Asset#getOperatingCompanyId()}）。两列混用的后果是同一账号
 * 在一处放行、另一处 403，排查成本极高，因此必须只认一列。
 *
 * <p>推导不到时返回 {@code null}，由调用方交给
 * {@link RbacService#assertCompanyAccess} 判定：受限账号一律拒绝、不受限账号放行 ——
 * 这正是「推导不到归属时按拒绝处理」的正确落点，不需要在这里另写一套拒绝逻辑。
 */
@Service
public class OwnershipResolver {

    private final AssetMapper assetMapper;
    private final ProjectMapper projectMapper;
    private final ProjectZoneMapper projectZoneMapper;
    private final ContractMapper contractMapper;
    private final VacateOrderMapper vacateOrderMapper;
    private final BillMapper billMapper;
    private final PaymentMapper paymentMapper;
    private final RefundOrderMapper refundOrderMapper;
    private final InvoiceMapper invoiceMapper;

    public OwnershipResolver(
            AssetMapper assetMapper,
            ProjectMapper projectMapper,
            ProjectZoneMapper projectZoneMapper,
            ContractMapper contractMapper,
            VacateOrderMapper vacateOrderMapper,
            BillMapper billMapper,
            PaymentMapper paymentMapper,
            RefundOrderMapper refundOrderMapper,
            InvoiceMapper invoiceMapper) {
        this.assetMapper = assetMapper;
        this.projectMapper = projectMapper;
        this.projectZoneMapper = projectZoneMapper;
        this.contractMapper = contractMapper;
        this.vacateOrderMapper = vacateOrderMapper;
        this.billMapper = billMapper;
        this.paymentMapper = paymentMapper;
        this.refundOrderMapper = refundOrderMapper;
        this.invoiceMapper = invoiceMapper;
    }

    /** 资产的归属公司 = 经营公司（设计 6.1 明确口径）。 */
    public Long ofAsset(Long assetId) {
        Asset asset = assetId == null ? null : assetMapper.selectById(assetId);
        return asset == null ? null : asset.getOperatingCompanyId();
    }

    public Long ofProject(Long projectId) {
        Project project = projectId == null ? null : projectMapper.selectById(projectId);
        return project == null ? null : project.getCompanyId();
    }

    /**
     * 分区的归属公司 = 所属项目的公司（分区本身没有公司列）。
     *
     * <p>刻意走 {@link ProjectZoneMapper#selectActiveById}：已软删的分区不应再解析出归属，
     * 否则删掉的分区还能继续被写入记录。
     */
    public Long ofZone(Long zoneId) {
        ProjectZone zone = zoneId == null ? null : projectZoneMapper.selectActiveById(zoneId);
        return zone == null ? null : ofProject(zone.getProjectId());
    }

    public Long ofContract(Long contractId) {
        Contract contract = contractId == null ? null : contractMapper.selectById(contractId);
        return contract == null ? null : ofAsset(contract.getAssetId());
    }

    /** 账单有 {@code asset_id} 时直接取，缺失才回落到 contract → asset。 */
    public Long ofBill(Long billId) {
        Bill bill = billId == null ? null : billMapper.selectById(billId);
        if (bill == null) {
            return null;
        }
        Long viaAsset = ofAsset(bill.getAssetId());
        return viaAsset != null ? viaAsset : ofContract(bill.getContractId());
    }

    public Long ofPayment(Long paymentId) {
        Payment payment = paymentId == null ? null : paymentMapper.selectById(paymentId);
        return payment == null ? null : ofContract(payment.getContractId());
    }

    /** 发票挂在收款或账单上，两条引用链都试一遍。 */
    public Long ofInvoice(Long invoiceId) {
        Invoice invoice = invoiceId == null ? null : invoiceMapper.selectById(invoiceId);
        if (invoice == null) {
            return null;
        }
        Long viaPayment = ofPayment(invoice.getPaymentId());
        return viaPayment != null ? viaPayment : ofBill(invoice.getBillId());
    }

    /** 退款单挂在收款上。 */
    public Long ofRefundOrder(Long refundOrderId) {
        RefundOrder order =
                refundOrderId == null ? null : refundOrderMapper.selectById(refundOrderId);
        return order == null ? null : ofPayment(order.getPaymentId());
    }

    /** 退租单挂在合同上。 */
    public Long ofVacateOrder(Long vacateOrderId) {
        VacateOrder order =
                vacateOrderId == null ? null : vacateOrderMapper.selectById(vacateOrderId);
        return order == null ? null : ofContract(order.getContractId());
    }
}

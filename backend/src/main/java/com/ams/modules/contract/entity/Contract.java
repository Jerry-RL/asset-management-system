package com.ams.modules.contract.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("contract")
public class Contract extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String contractNo;
    private Long assetId;
    private Long tenantId;
    @Version
    private Integer version;
    private Long parentContractId;
    private Long bundleId;
    /** normal / combo / split */
    private String leaseMode;
    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal leaseArea;
    private String rentType; // fixed_monthly/fixed_yearly/per_area/per_unit/negotiable
    private BigDecimal rentAmount;
    private BigDecimal depositAmount;
    private BigDecimal prepayAmount;
    private String paymentCycle; // monthly/quarterly/yearly
    private Integer freeRentDays;
    private BigDecimal increaseRate;
    private String increasePeriod;
    private Integer graceDays;
    private String prorationBase; // calendar / fixed_30
    private String contractType;
    private String status; // 合同状态机九态
    private String esignStatus;
    private String esignFlowId;
    private LocalDateTime esignSignedAt;
    private Long esignEvidenceFileId;
    private String paymentStatus;
    private String remark;
    /** fifo / specified / proportional */
    private String allocationStrategy;
    private Boolean lateFeeFirst;
    /** 低于底价，待超低价特批 */
    private Boolean specialApprovalRequired;
    /** 超低价特批已通过 */
    private Boolean belowFloorCleared;
    /** 生成文档所用模板 */
    private Long templateId;
    /** 已生成 Word 文件 ID（file_metadata） */
    private Long docFileId;
    /** 填充后的合同正文 HTML（在线预览） */
    private String docHtml;
}

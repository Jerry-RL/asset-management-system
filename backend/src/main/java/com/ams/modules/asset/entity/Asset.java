package com.ams.modules.asset.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("asset")
public class Asset extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;
    /** 所属项目分区（project_zone.id）；分区面积由该字段汇总得出 */
    private Long zoneId;
    /** 资产公司（org_company.id）：项目、责任部门均按此级联 */
    private Long assetCompanyId;
    private String assetNo;
    private String name;
    private String assetType; // property / land
    private BigDecimal area;
    /** 租赁面积(㎡) */
    private BigDecimal leaseArea;
    /** 分区楼层 */
    private Integer floorNo;
    private String sourceType;
    private String ownershipType;
    /** 部分租赁状态 / 资产性质 / 建筑规划，取值见对应 sys_dict_type.code */
    private String partialLeaseStatus;
    private String assetNature;
    private String buildingPlan;
    private Long propertyCompanyId;
    private Long operatingCompanyId;
    private String leaseControlStatus;
    /** 登记入库时间 */
    private LocalDate registeredAt;
    /** 责任部门（org_department.id），取自资产公司下属部门 */
    private Long responsibleDepartmentId;
    /** 责任人（sys_user.id），取自责任部门下员工 */
    private Long responsibleUserId;
    private BigDecimal baseRentAssessed;
    private BigDecimal baseRentFloor;
    private BigDecimal marketRefRent;
    private String province;
    private String city;
    private String district;
    private String address;
    private String structureType;
    private String usageType;
    /** 户型，取值见 sys_dict_type.code = asset_house_type */
    private String houseType;
    private String waterMeterNo;
    private String electricMeterNo;
    /** 原值(万元) */
    private BigDecimal originalValue;
    /** 资产图片（对象存储 URL 与附件 ID） */
    private String imageUrl;
    private Long imageFileId;
    private String qrCodeUrl;
    private Long parentAssetId;
    private String vacantReason;
    private java.time.LocalDateTime vacantSince;
    private BigDecimal longitude;
    private BigDecimal latitude;
    /** active / frozen / merged_out */
    private String structureStatus;
    private Long rootAssetId;
    private String oldAssetNo;
    /** in_book / exited；处置完成置 exited（ADR-0019：生命周期而非占用状态） */
    private String lifecycleStatus;
    /** 占用率（%）＝ 生效占用面积 / 单元面积合计；由 LeaseStatusDeriver 派生 */
    private BigDecimal occupancyRatio;

    /** 所属分区名称（非表字段，列表/详情回显用） */
    @TableField(exist = false)
    private String zoneName;
    /** 所属项目名称（非表字段，避免列表露出裸 projectId） */
    @TableField(exist = false)
    private String projectName;
    /**
     * 所属项目的「项目属性」（非表字段，取自 project.type，取值见
     * sys_dict_type.code = project_property）；列表展示与「资产来源」级联筛选均以此为口径。
     */
    @TableField(exist = false)
    private String projectType;
    /** 资产公司名称（非表字段） */
    @TableField(exist = false)
    private String assetCompanyName;
    /** 产权公司名称（非表字段） */
    @TableField(exist = false)
    private String propertyCompanyName;
    /** 责任部门名称（非表字段） */
    @TableField(exist = false)
    private String responsibleDepartmentName;
    /** 责任人姓名（非表字段） */
    @TableField(exist = false)
    private String responsibleUserName;

    @Version
    private Integer version;
}

package com.ams.modules.asset.dto;

import com.ams.modules.asset.entity.ProjectZone;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 项目新增/编辑请求：第一步基本信息 + 第二步分区配置。
 */
@Data
public class ProjectSaveRequest {

    // ---- 第一步：基本信息 ----
    private Long companyId;
    private String name;
    private String address;
    private String province;
    private String city;
    private String district;
    /** park 园区 / building 楼宇 / land 地块 / other 其他 */
    private String type;
    /** 1 正常 / 0 停用 */
    private Integer status;
    private String imageUrl;
    private Long imageFileId;
    private BigDecimal longitude;
    private BigDecimal latitude;

    // ---- 第二步：项目分区配置 ----
    private List<ProjectZone> zones = new ArrayList<>();
}

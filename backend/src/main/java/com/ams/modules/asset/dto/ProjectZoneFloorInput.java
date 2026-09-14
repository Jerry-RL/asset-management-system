package com.ams.modules.asset.dto;

import java.math.BigDecimal;
import lombok.Data;

/**
 * 分区楼层的**读写共用** DTO。
 *
 * <p>与 {@code ProjectZone} 不同，这里刻意不让实体直接当请求体：实体映射了
 * {@code deleted_at}，暴露成请求体等于把「软删自己」的能力交给客户端。
 * 读写同形也让字段清单只有一份（{@code id} 在写路径上被服务端忽略，归属一律取路径参数）。
 */
@Data
public class ProjectZoneFloorInput {

    /** 读路径回显；写路径忽略（归属与主键都由 URL 决定）。 */
    private Long id;

    /** 只读：所属分区 id（写路径忽略，归属一律取 URL 上的分区）。 */
    private Long zoneId;

    /** 楼层号：必填，同分区内唯一。负数表示地下层。 */
    private Integer floorNo;

    /** 展示名，可空。 */
    private String name;

    private String remark;

    /** 只读：该层资产宗数。 */
    private Long assetCount;

    /** 只读：该层资产面积合计(㎡)。 */
    private BigDecimal assetArea;
}

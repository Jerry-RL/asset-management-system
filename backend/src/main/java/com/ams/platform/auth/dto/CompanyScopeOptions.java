package com.ams.platform.auth.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 全局公司切换的可选项（顶栏公司下拉数据源）。
 *
 * <p>可切换范围由服务端按登录用户的角色数据范围计算，客户端只负责展示与选择：
 * {@link #unrestricted} 为 true（super_admin / 数据范围 all）时额外提供「全部公司」选项。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyScopeOptions {

    /** 可切换公司列表，按公司树顺序（父在子前）返回 */
    private List<Item> companies;

    /** 用户所属公司 ID（固定值，用于「本司」高亮与回落） */
    private Long homeCompanyId;

    /**
     * 当前生效公司 ID。
     * {@link #unrestricted} 为 true 且未显式切换时为 null，前端对应选中「全部公司」。
     */
    private Long activeCompanyId;

    /** 数据范围是否不受公司限制（super_admin / 数据范围 all） */
    private boolean unrestricted;

    /** 是否已显式切换到某个公司（未切换时高亮「全部公司」或所属公司） */
    private boolean scoped;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private Long id;
        private String name;
        private String shortName;
        /** 上级公司 ID，前端据此展示层级缩进 */
        private Long parentId;
    }
}

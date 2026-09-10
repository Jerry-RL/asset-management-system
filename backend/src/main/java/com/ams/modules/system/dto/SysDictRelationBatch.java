package com.ams.modules.system.dto;

import java.util.List;
import lombok.Data;

/**
 * 字典关联批量保存请求：一次性替换某字典的全部关联。
 *
 * <p>{@code groups} 中未出现或 {@code itemIds} 为空的目标字典，表示不建立关联。
 */
@Data
public class SysDictRelationBatch {

    /** 拥有该关联的字典 ID */
    private Long sourceTypeId;

    private List<Group> groups;

    @Data
    public static class Group {

        /** 被关联的字典 ID */
        private Long targetTypeId;

        /** 被关联的字典项 ID 列表 */
        private List<Long> itemIds;
    }
}

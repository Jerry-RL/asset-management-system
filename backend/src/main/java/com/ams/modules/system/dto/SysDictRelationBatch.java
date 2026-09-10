package com.ams.modules.system.dto;

import java.util.List;
import lombok.Data;

/**
 * 字典关联批量保存请求：一次性替换某字典的全部关联。
 *
 * <p>{@code groups} 中未出现或 {@code itemIds} 为空的目标字典，表示不建立关联。
 * 每个分组可携带 {@code sourceItemId}：
 * <ul>
 *   <li>为空：字典级关联（整本字典 → 目标字典的若干项）；
 *   <li>非空：字典项级级联（父字典的该项 → 目标字典的若干项），将成为子字典下拉的白名单。
 * </ul>
 */
@Data
public class SysDictRelationBatch {

    /** 拥有该关联的字典 ID */
    private Long sourceTypeId;

    private List<Group> groups;

    @Data
    public static class Group {

        /** 父字典项 ID；为空表示字典级关联 */
        private Long sourceItemId;

        /** 被关联的字典 ID */
        private Long targetTypeId;

        /** 被关联的字典项 ID 列表 */
        private List<Long> itemIds;
    }
}

package com.ams.common.web;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分页结果（对齐 docs/api/README.md §3.2）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    private List<T> list;
    private long total;
    private long page;
    private long pageSize;

    public static <T> PageResult<T> of(List<T> list, long total, long page, long pageSize) {
        return PageResult.<T>builder()
                .list(list)
                .total(total)
                .page(page)
                .pageSize(pageSize)
                .build();
    }
}

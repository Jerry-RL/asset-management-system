package com.ams.common.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 分页查询基类（page 从 1 开始，pageSize 最大 100）。
 */
@Data
public class PageRequest {

    @Min(1)
    private long page = 1;

    @Min(1)
    @Max(100)
    private long pageSize = 10;

    public long offset() {
        return (page - 1) * pageSize;
    }
}

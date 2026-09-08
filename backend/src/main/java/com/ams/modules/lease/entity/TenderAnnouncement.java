package com.ams.modules.lease.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

@Data
@TableName("tender_announcement")
public class TenderAnnouncement {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;
    private String assetIds; // 存储为逗号分隔字符串（PG bigint[] 简化处理）
    private LocalDateTime registerDeadline;
    private Integer displayPeriodDays;
    private String status; // open/closed/flowed
    private String result;
    private String filingPackageJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public List<Long> assetIdList() {
        if (assetIds == null || assetIds.isBlank()) {
            return List.of();
        }
        String[] parts = assetIds.replace("{", "").replace("}", "").split(",");
        return java.util.Arrays.stream(parts).filter(s -> !s.isBlank())
                .map(s -> Long.valueOf(s.trim())).toList();
    }
}

package com.ams.modules.opscalendar.dto;

import java.time.LocalDate;
import lombok.Builder;
import lombok.Data;

/** 经营日历事件（业务聚合 + 手工提醒）。 */
@Data
@Builder
public class OpsCalendarEvent {

    /** 稳定键：type:bizId 或 note:id */
    private String id;
    /** contract_expiry / bill_due / task_deadline / mortgage_expiry / occupation_end / self_use_end / inspection / repair_sla / note */
    private String type;
    private String title;
    private String summary;
    private LocalDate eventDate;
    /** 1=低 2=中 3=高 */
    private int level;
    private String status;
    private String bizType;
    private Long bizId;
    /** 前端跳转 path，如 /contracts */
    private String linkPath;
}

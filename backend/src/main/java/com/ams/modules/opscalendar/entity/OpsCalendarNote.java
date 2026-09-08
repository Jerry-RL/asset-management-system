package com.ams.modules.opscalendar.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_calendar_note")
public class OpsCalendarNote extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDate eventDate;
    private String title;
    private String content;
    private Integer level;
    private Long companyId;
}

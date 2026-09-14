package com.ams.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("operation_log")
public class OperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String username;
    private String module;
    private String action;
    private Long refId;
    private String detailJson;
    private String ip;
    private String traceId;
    private LocalDateTime createdAt;

    /**
     * 本次操作是否成功（V51 新增）。
     *
     * <p>刻意<b>可空</b>：V51 之前的存量行当时没有记录成败，{@code null} = 未知。
     * 查询页把它显示为「—」，只有显式按「成功」筛选时才被排除。
     */
    private Boolean success;

    /**
     * 失败原因（V51 新增）：异常 message 截断 500；成功行为 {@code null}。
     *
     * <p>此前失败信息埋在 {@code detail_json.error} 里，想筛「哪些操作失败了」必须查 JSON，
     * 而这是审计最核心的问题之一。
     */
    private String error;
}

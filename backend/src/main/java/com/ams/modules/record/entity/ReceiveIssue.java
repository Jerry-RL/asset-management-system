package com.ams.modules.record.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 遗留问题（{@code biz_receive_issue}）—— 设计 §4.1，接收信息的子表。
 *
 * <p>{@link #receiveId} 由服务端按所属接收记录赋值，**不接受客户端传入**，
 * 从结构上杜绝把一条 issue 拼到别的接收记录上（设计 §5.4）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_receive_issue")
public class ReceiveIssue extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long receiveId;
    private String issueType;
    private String description;
    private Long discovererId;
    private String discovererName;
    private Integer sort;
    private LocalDateTime deletedAt;
}

package com.ams.modules.record.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 接收信息（{@code biz_receive_record}）—— 设计 §4.1，1:N。
 *
 * <p>{@link #handoverUserId} 与 {@link #handoverUserName} 是「混合相对人」：内员填 id、姓名由后端
 * 用员工姓名覆盖（姓名快照）；外部人员 id 为空、姓名手填。见设计 §4.4。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_receive_record")
public class ReceiveRecord extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String ownerType;
    private Long ownerId;
    private String handoverType;
    private String docName;
    private Long handoverUserId;
    private String handoverUserName;
    private LocalDate handoverDate;
    private String remark;
    private LocalDateTime deletedAt;
}

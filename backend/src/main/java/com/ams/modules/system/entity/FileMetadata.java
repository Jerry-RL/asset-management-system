package com.ams.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("file_metadata")
public class FileMetadata {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String bucket;
    private String objectKey;
    private String fileName;
    private String contentType;
    private String hashSha256;
    private Long size;
    private String bizType;
    private Long createdBy;
    private LocalDateTime createdAt;
}

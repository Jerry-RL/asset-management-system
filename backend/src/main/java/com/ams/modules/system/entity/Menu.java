package com.ams.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("menu")
public class Menu {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long parentId;
    private String name;
    private String code;
    private String path;
    private String icon;
    private Integer sort;
    private String menuType;
    private Integer status;
    private LocalDateTime createdAt;
}

package com.ams.platform.security;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 固定动作词表（设计 4.2）。
 *
 * <p>权限码统一为 {@code menuCode:action}，动作只能取自本枚举 —— 词表集中定义在后端，
 * 前端经 {@code GET /system/permission-actions} 下发，不硬编码两份，避免矩阵与断言漂移。
 *
 * <p>历史来源：V2 注释里的 {@code add} / {@code edit} 已废弃，V45 迁移把存量数据
 * 归一到 {@link #CREATE} / {@link #UPDATE}。
 */
public enum PermissionAction {

    /** 页面可见性判定唯一认可的动作：菜单可见 ⇔ 角色拥有该 code:view（设计 4.1）。 */
    VIEW("view"),
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    EXPORT("export"),
    IMPORT("import"),
    APPROVE("approve"),
    AUDIT("audit"),
    /** 提权能力：授予角色权限与数据范围（设计 4.5）。 */
    ASSIGN("assign");

    private final String code;

    PermissionAction(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<PermissionAction> of(String code) {
        return Arrays.stream(values()).filter(a -> a.code.equals(code)).findFirst();
    }

    public static boolean isValid(String code) {
        return of(code).isPresent();
    }

    /** 词表（按枚举声明顺序，供前端矩阵渲染）。 */
    public static List<String> codes() {
        return Arrays.stream(values()).map(PermissionAction::code).toList();
    }
}

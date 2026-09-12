package com.ams.modules.record;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import java.util.Arrays;
import java.util.Optional;

/**
 * 后续记录的**根主体**（设计 §5.2）：记录挂在哪一类对象上，决定权限码与归属解析方式。
 *
 * <p>权限码刻意只有两个：资产走 {@code asset.ledger}，项目与分区共用 {@code asset.project}
 * —— 分区是项目配置的一部分，为它单独造一个菜单码会让权限矩阵多出一行没有实际角色的权限点。
 */
public enum RecordOwnerType {

    ASSET("asset", "asset.ledger"),
    PROJECT("project", "asset.project"),
    ZONE("zone", "asset.project");

    private final String code;
    private final String menuCode;

    RecordOwnerType(String code, String menuCode) {
        this.code = code;
        this.menuCode = menuCode;
    }

    public String code() {
        return code;
    }

    /** 读写该主体记录所需的菜单码（动作由调用点决定：读 view、写 update）。 */
    public String menuCode() {
        return menuCode;
    }

    public static Optional<RecordOwnerType> ofCode(String code) {
        return Arrays.stream(values()).filter(t -> t.code.equals(code)).findFirst();
    }

    /** 解析宿主类型；未知值直接拒绝，不退回默认主体（退回会让越权写在错误的主体上）。 */
    public static RecordOwnerType fromCode(String code) {
        return ofCode(code)
                .orElseThrow(() -> new AppException(
                        ErrorCode.BAD_REQUEST, "不支持的记录归属类型：" + code));
    }
}

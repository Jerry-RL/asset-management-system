package com.ams.platform.observability;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 来源端（{@code app_log.app_type}）。
 *
 * <p>不加 DB CHECK 约束：加一个前端就要改 DDL 不划算，而 ingest 是唯一入口，
 * 由本枚举做白名单校验即可。
 */
public enum AppType {

    ADMIN_WEB("admin-web"),
    H5_TENANT("h5-tenant"),
    H5_WORKER("h5-worker"),
    TENANT_MP("tenant-mp"),
    WORKER_MP("worker-mp"),
    /** 后端自身（{@code GlobalExceptionHandler} 落库）。 */
    BACKEND("backend");

    private final String code;

    AppType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<AppType> of(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        for (AppType type : values()) {
            if (type.code.equalsIgnoreCase(code.trim())) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /**
     * 允许<strong>端侧 ingest 上报</strong>的取值 —— 刻意不含 {@link #BACKEND}。
     *
     * <p>后端异常由 {@code AppLogRecorder} 直接调 Service 落库，不经过 ingest 白名单。
     * 若把 {@code backend} 也开放给 ingest，外部就能把记录伪装成「后端抛异常」，
     * 在真实故障排查时把人引到错误方向。拒绝它没有任何代价。
     */
    public static List<String> clientReportableCodes() {
        return Arrays.stream(values())
                .filter(type -> type != BACKEND)
                .map(AppType::code)
                .toList();
    }
}

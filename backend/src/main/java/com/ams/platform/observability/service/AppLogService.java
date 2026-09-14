package com.ams.platform.observability.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.PageResult;
import com.ams.platform.observability.AppLog;
import com.ams.platform.observability.AppLogFingerprint;
import com.ams.platform.observability.dto.AppLogPurgeRequest;
import com.ams.platform.observability.dto.AppLogStats;
import com.ams.platform.observability.dto.AppLogView;
import com.ams.platform.observability.mapper.AppLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 应用日志的写入、查询与清理（设计 §7.3、§8、§11）。
 *
 * <p>写入路径<strong>一律吞异常</strong>：日志系统的故障绝不允许变成业务故障
 * （设计 §3.1 硬边界 2）。
 */
@Service
public class AppLogService {

    private static final Logger log = LoggerFactory.getLogger(AppLogService.class);

    /** 列表查询的 pageSize 上限：防止一次把整表拉进内存。 */
    public static final int MAX_PAGE_SIZE = 200;

    /** 清理时每批删除的行数上限：避免一个长事务锁表。 */
    static final int PURGE_BATCH_SIZE = 5000;

    /** 清理循环的批数上限：单次调用最多删 10 万行，防止误配的天数把关卡住。 */
    static final int PURGE_MAX_BATCHES = 20;

    private final AppLogMapper mapper;
    private final AppLogAlertService alertService;
    private final ObjectMapper objectMapper;

    public AppLogService(
            AppLogMapper mapper, AppLogAlertService alertService, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.alertService = alertService;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    /**
     * 落库并触发告警评估。
     *
     * <p>指纹在<strong>服务端</strong>计算（设计 §7.3）：客户端可伪造指纹就能绕过告警冷却。
     *
     * <p>异常全部吞掉——写日志失败不能影响调用它的业务流程。
     *
     * @return 落库后的实体（带 id）；写入失败或指纹不可用时返回 null
     */
    public AppLog record(AppLog entry, String stackHead) {
        if (entry == null) {
            return null;
        }
        try {
            if (entry.getFingerprint() == null || entry.getFingerprint().isBlank()) {
                entry.setFingerprint(AppLogFingerprint.compute(
                        entry.getSource(), entry.getAppType(), entry.getMessage(), stackHead));
            }
            if (entry.getOccurredAt() == null) {
                entry.setOccurredAt(LocalDateTime.now());
            }
            if (entry.getCreatedAt() == null) {
                entry.setCreatedAt(LocalDateTime.now());
            }
            mapper.insert(entry);
            alertService.onRecord(entry);
            return entry;
        } catch (Exception ex) {
            log.warn("应用日志写入失败（不影响业务）：{}", ex.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /**
     * 分页查询。
     *
     * <p>时间范围作用于 {@code created_at}（服务端时间）而非 {@code occurred_at}：
     * 端侧时钟不可信，按它过滤会漏掉或误纳记录（设计 §4.2）。
     */
    public PageResult<AppLogView> query(
            String level,
            String appType,
            String source,
            String keyword,
            String traceId,
            LocalDateTime from,
            LocalDateTime to,
            long page,
            long pageSize) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        LambdaQueryWrapper<AppLog> wrapper = new LambdaQueryWrapper<AppLog>()
                .eq(hasText(level), AppLog::getLevel, upper(level))
                .eq(hasText(appType), AppLog::getAppType, appType)
                .eq(hasText(source), AppLog::getSource, source)
                .eq(hasText(traceId), AppLog::getTraceId, traceId)
                .like(hasText(keyword), AppLog::getMessage, keyword)
                .ge(from != null, AppLog::getCreatedAt, from)
                .le(to != null, AppLog::getCreatedAt, to)
                .orderByDesc(AppLog::getId);

        IPage<AppLog> result = mapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        // 不返回密码哈希之类的东西（本表没有），但 extra 需要解析：统一走 toView
        List<AppLogView> list =
                result.getRecords().stream().map(this::toView).toList();
        return PageResult.of(list, result.getTotal(), safePage, safeSize);
    }

    /** 单条详情；不存在时抛 404（与其他模块一致）。 */
    public AppLogView detail(Long id) {
        AppLog log = mapper.selectById(id);
        if (log == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return toView(log);
    }

    /**
     * 按 traceId 取全链路记录。
     *
     * <p>按 {@code created_at} <strong>升序</strong>：链路的语义是「先发生什么、后发生什么」，
     * 倒序展示会让人把因果关系读反（例如把后端的异常当成前端报错的原因）。
     *
     * <p><strong>只返回 app_log</strong>，不含 {@code operation_log}
     * （该表目前全仓只写不读，此处展示等于给审计数据开一条新的读取通道，见设计 §8.1）。
     */
    public List<AppLogView> trace(String traceId) {
        if (!hasText(traceId)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "traceId 不能为空");
        }
        return mapper
                .selectList(new LambdaQueryWrapper<AppLog>()
                        .eq(AppLog::getTraceId, traceId)
                        .orderByAsc(AppLog::getCreatedAt)
                        .orderByAsc(AppLog::getId))
                .stream()
                .map(this::toView)
                .toList();
    }

    /**
     * 近 24h 统计：各级别/各端计数 + Top 指纹。
     *
     * <p>用三次 <strong>GROUP BY 聚合查询</strong>而非「把 24h 的行全捞出来在内存里 group」：
     * 后者在真实流量下会把整个窗口的数据读进 JVM 堆，统计接口反而成了 OOM 的风险点。
     * 聚合后每个查询只返回几行到十几行。
     */
    public AppLogStats stats() {
        LocalDateTime from = LocalDateTime.now().minusHours(24);

        List<Map<String, Object>> levelRows = mapper.selectMaps(new QueryWrapper<AppLog>()
                .select("level", "count(*) AS cnt")
                .ge("created_at", from)
                .groupBy("level"));
        List<Map<String, Object>> appTypeRows = mapper.selectMaps(new QueryWrapper<AppLog>()
                .select("app_type", "count(*) AS cnt")
                .ge("created_at", from)
                .groupBy("app_type"));
        List<Map<String, Object>> topRows = mapper.selectMaps(new QueryWrapper<AppLog>()
                .select("fingerprint", "count(*) AS cnt", "min(level) AS level",
                        "min(app_type) AS app_type", "min(message) AS message")
                .ge("created_at", from)
                .groupBy("fingerprint")
                .orderByDesc("cnt")
                .last("LIMIT 10"));

        List<AppLogStats.CountItem> byLevel = toCountItems(levelRows, "level");
        List<AppLogStats.CountItem> byAppType = toCountItems(appTypeRows, "app_type");
        long total = byLevel.stream().mapToLong(AppLogStats.CountItem::count).sum();

        List<AppLogStats.TopError> top = new ArrayList<>();
        for (Map<String, Object> row : topRows) {
            top.add(new AppLogStats.TopError(
                    str(row.get("fingerprint")),
                    toLong(row.get("cnt")),
                    str(row.get("level")),
                    str(row.get("app_type")),
                    str(row.get("message"))));
        }
        return new AppLogStats(24, total, byLevel, byAppType, top);
    }

    // ------------------------------------------------------------------
    // 清理
    // ------------------------------------------------------------------

    /**
     * 删除 {@code created_at < before} 的记录，必要时按端/级别收窄。
     *
     * <p>{@code before} 由调用方强制要求（Controller 层校验），这里是第二道防线 ——
     * 没有时间边界的删除等于「清空全表」，而日志是事后追查的唯一线索。
     *
     * @return 删除的行数
     */
    public int purge(AppLogPurgeRequest request) {
        if (request == null || request.getBefore() == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "必须指定清理时间边界 before");
        }
        int deleted = 0;
        for (int batch = 0; batch < PURGE_MAX_BATCHES; batch++) {
            LambdaQueryWrapper<AppLog> wrapper = new LambdaQueryWrapper<AppLog>()
                    .lt(AppLog::getCreatedAt, request.getBefore())
                    .eq(hasText(request.getAppType()), AppLog::getAppType, request.getAppType())
                    .eq(hasText(request.getLevel()), AppLog::getLevel, upper(request.getLevel()))
                    .orderByAsc(AppLog::getId)
                    .last("LIMIT " + PURGE_BATCH_SIZE);
            List<AppLog> rows = mapper.selectList(wrapper);
            if (rows.isEmpty()) {
                break;
            }
            List<Long> ids = rows.stream().map(AppLog::getId).toList();
            deleted += mapper.deleteByIds(ids);
            if (rows.size() < PURGE_BATCH_SIZE) {
                break;
            }
        }
        return deleted;
    }

    /** 定时清理：删除超过保留天数的记录。 */
    public int purgeExpired(int retentionDays) {
        AppLogPurgeRequest request = new AppLogPurgeRequest();
        request.setBefore(LocalDateTime.now().minusDays(Math.max(0, retentionDays)));
        return purge(request);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** extra 是库里的 JSON 字符串，对外返回解析后的对象（前端直接渲染）。 */
    private AppLogView toView(AppLog log) {
        return new AppLogView(
                log.getId(),
                log.getTraceId(),
                log.getLevel(),
                log.getAppType(),
                log.getSource(),
                log.getFingerprint(),
                log.getMessage(),
                parseExtra(log.getExtra()),
                log.getUa(),
                log.getUrl(),
                log.getUserId(),
                log.getClientIp(),
                log.getOccurredAt(),
                log.getCreatedAt());
    }

    /**
     * 解析 extra；非法 JSON 时原样以字符串返回。
     *
     * <p>不抛异常也不丢弃：库里可能有手工插入或历史格式的数据，因为一条 extra 解析失败
     * 就让整个列表报错是不划算的。
     */
    private Object parseExtra(String extra) {
        if (extra == null || extra.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(extra, Object.class);
        } catch (Exception ex) {
            return extra;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    /** 把 {@code {key, cnt}} 形式的分组结果转成计数项，并按次数降序。 */
    private static List<AppLogStats.CountItem> toCountItems(
            List<Map<String, Object>> rows, String keyColumn) {
        List<AppLogStats.CountItem> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String key = str(row.get(keyColumn));
            items.add(new AppLogStats.CountItem(key == null ? "unknown" : key, toLong(row.get("cnt"))));
        }
        items.sort((a, b) -> Long.compare(b.count(), a.count()));
        return items;
    }
}

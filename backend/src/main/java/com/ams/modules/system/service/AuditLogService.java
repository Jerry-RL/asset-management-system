package com.ams.modules.system.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.PageResult;
import com.ams.modules.system.dto.LoginLogView;
import com.ams.modules.system.dto.OperationLogView;
import com.ams.modules.system.entity.LoginLog;
import com.ams.modules.system.entity.OperationLog;
import com.ams.modules.system.mapper.LoginLogMapper;
import com.ams.modules.system.mapper.OperationLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 审计日志查询（FR-COM-004：按时间、用户、模块查询系统日志与登录日志）。
 *
 * <p><b>这是全仓第一个读取 {@code operation_log} / {@code login_log} 的地方。</b>
 * 此前两个 Mapper 只被 insert 调用。因此本类只有读方法，<b>没有任何删除 / 清理入口</b> ——
 * 用户故事地图明确「操作日志不可删」，NFR-DSEC-016 要求保留 ≥3 年（设计 §3.1 硬边界 3）。
 *
 * <p>时间范围一律作用于 {@code created_at}（服务端时间）。两表都没有端侧自报时间，
 * 不存在 app_log 那种「时钟不可信」的问题，但仍要明确只有这一个基准。
 */
@Service
public class AuditLogService {

    /** 列表查询的 pageSize 上限：与 AppLogService 同口径，防止一次把整表拉进内存。 */
    public static final int MAX_PAGE_SIZE = 200;

    private final OperationLogMapper operationLogMapper;
    private final LoginLogMapper loginLogMapper;
    private final ObjectMapper objectMapper;

    public AuditLogService(
            OperationLogMapper operationLogMapper,
            LoginLogMapper loginLogMapper,
            ObjectMapper objectMapper) {
        this.operationLogMapper = operationLogMapper;
        this.loginLogMapper = loginLogMapper;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 操作日志
    // ------------------------------------------------------------------

    /**
     * 分页查询操作日志。
     *
     * <p>{@code success} 缺省时<b>不过滤</b>，因此 V51 之前的存量行（{@code success IS NULL}）
     * 照常出现；只有显式传 {@code false} 才把未知行一并排除。前端把 NULL 显示为「—」。
     *
     * <p>{@code refId} 是「被操作对象的 id」（{@code AuditRefIdResolver} 推断写入）。
     * 这是审计最常用的提问方式 ——「这个对象上发生过什么」—— 用等值条件就能回答，
     * 不必对 {@code detail_json} 做 {@code LIKE}。批量操作（导入 / 合并 / 清理）的
     * {@code ref_id} 为 {@code NULL}，按 {@code refId} 查时不会命中，这是刻意的。
     *
     * <p>{@code keyword} 覆盖用户名 / 动作 / 入参 JSON 三处：FR-COM-004 只要求按时间、用户、
     * 模块查询，动作不单列参数，用关键字命中即可（设计 §5.2）。
     */
    public PageResult<OperationLogView> query(
            LocalDateTime from,
            LocalDateTime to,
            String username,
            String module,
            Boolean success,
            String keyword,
            String traceId,
            Long refId,
            long page,
            long pageSize) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        LambdaQueryWrapper<OperationLog> wrapper = new LambdaQueryWrapper<OperationLog>()
                .ge(from != null, OperationLog::getCreatedAt, from)
                .le(to != null, OperationLog::getCreatedAt, to)
                .like(hasText(username), OperationLog::getUsername, username)
                .eq(hasText(module), OperationLog::getModule, module)
                .eq(success != null, OperationLog::getSuccess, success)
                .eq(hasText(traceId), OperationLog::getTraceId, traceId)
                // 非正数（前端手改 URL 传 0 / -1）当作「没选」：主键非正不可能命中任何行，
                // 真发下去只会得到「空列表 + 筛选框显示已填」这种最难自查的状态
                .eq(refId != null && refId > 0, OperationLog::getRefId, refId);
        if (hasText(keyword)) {
            // 必须包一层 and(...)：直接 or() 会与上面的 eq/ge 同级，
            // 让「模块=asset AND 关键字命中」退化成「模块=asset OR 关键字命中」
            wrapper.and(w -> w.like(OperationLog::getUsername, keyword)
                    .or().like(OperationLog::getAction, keyword)
                    .or().like(OperationLog::getDetailJson, keyword));
        }
        // created_at DESC 之外再按 id DESC 兜底：同一秒内的多条记录若排序不稳定，
        // 翻页会漏行 / 重行
        wrapper.orderByDesc(OperationLog::getCreatedAt).orderByDesc(OperationLog::getId);

        IPage<OperationLog> result = operationLogMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        List<OperationLogView> list = result.getRecords().stream().map(this::toView).toList();
        return PageResult.of(list, result.getTotal(), safePage, safeSize);
    }

    /** 单条详情；不存在时抛 404（与其他模块一致）。 */
    public OperationLogView detail(Long id) {
        OperationLog row = operationLogMapper.selectById(id);
        if (row == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return toView(row);
    }

    /**
     * 去重、升序的模块清单（筛选下拉的数据源）。
     *
     * <p>取值来自 {@code @Audited(module = …)} 里实际写过的字符串，由数据库现状决定，
     * 刻意<b>不硬编码</b>：写死必然与注解漂移，而筛选下拉的价值恰恰是「只给确实查得到的值」。
     */
    public List<String> modules() {
        return operationLogMapper
                .selectObjs(new QueryWrapper<OperationLog>()
                        .select("DISTINCT module")
                        .isNotNull("module")
                        .orderByAsc("module"))
                .stream()
                .map(String::valueOf)
                .toList();
    }

    // ------------------------------------------------------------------
    // 登录日志
    // ------------------------------------------------------------------

    /**
     * 分页查询登录日志。
     *
     * <p>{@code result} 的取值是<b>小写</b> {@code success} / {@code failed}（AuthService 的写入口径），
     * 等值匹配而不做大小写归一 —— 归一反而会掩盖前端传错大小写这一常见事故。
     */
    public PageResult<LoginLogView> queryLoginLogs(
            LocalDateTime from,
            LocalDateTime to,
            String username,
            String result,
            String ip,
            long page,
            long pageSize) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        LambdaQueryWrapper<LoginLog> wrapper = new LambdaQueryWrapper<LoginLog>()
                .ge(from != null, LoginLog::getCreatedAt, from)
                .le(to != null, LoginLog::getCreatedAt, to)
                .like(hasText(username), LoginLog::getUsername, username)
                .eq(hasText(result), LoginLog::getResult, result)
                .eq(hasText(ip), LoginLog::getIp, ip)
                .orderByDesc(LoginLog::getCreatedAt)
                .orderByDesc(LoginLog::getId);

        IPage<LoginLog> pageResult = loginLogMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        List<LoginLogView> list = pageResult.getRecords().stream().map(this::toLoginView).toList();
        return PageResult.of(list, pageResult.getTotal(), safePage, safeSize);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private OperationLogView toView(OperationLog row) {
        return new OperationLogView(
                row.getId(),
                row.getUserId(),
                row.getUsername(),
                row.getModule(),
                row.getAction(),
                row.getRefId(),
                parseDetail(row.getDetailJson()),
                row.getIp(),
                row.getTraceId(),
                row.getCreatedAt(),
                row.getSuccess(),
                row.getError());
    }

    private LoginLogView toLoginView(LoginLog row) {
        return new LoginLogView(
                row.getId(),
                row.getUserId(),
                row.getUsername(),
                row.getIp(),
                row.getResult(),
                row.getFailReason(),
                row.getCreatedAt());
    }

    /**
     * 解析 {@code detail_json}；非法 JSON 时<b>原样以字符串返回</b>。
     *
     * <p>不抛异常也不丢弃：库里可能有手工插入或历史格式的数据（该列的形状在 V51 之前
     * 是 {@code {args, error}}、之后是 {@code {args}}），因为一条脏数据就让整个列表报错
     * 是不划算的。
     */
    private Object parseDetail(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(detailJson, Object.class);
        } catch (Exception ex) {
            return detailJson;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

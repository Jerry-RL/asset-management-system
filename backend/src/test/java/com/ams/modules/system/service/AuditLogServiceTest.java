package com.ams.modules.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.common.exception.AppException;
import com.ams.common.web.PageResult;
import com.ams.modules.system.dto.OperationLogView;
import com.ams.modules.system.entity.LoginLog;
import com.ams.modules.system.entity.OperationLog;
import com.ams.modules.system.mapper.LoginLogMapper;
import com.ams.modules.system.mapper.OperationLogMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 审计查询的服务层（设计 §5）。
 *
 * <p>三条重点：
 * <ol>
 *   <li><b>{@code success} 缺省 = 不过滤</b>：V51 之前的存量行 {@code success IS NULL}，
 *       若把缺省当成 {@code false} 或补成 {@code IS NOT NULL}，历史审计记录会凭空消失；</li>
 *   <li><b>关键字必须整体加括号</b>：{@code module = ? OR username LIKE ?} 与
 *       {@code module = ? AND (username LIKE ? OR …)} 是两条不同的查询 ——
 *       前者会让「模块筛选」失效，把别的模块的日志也捞出来；</li>
 *   <li><b>pageSize 收窄到上限</b>：不设上限等于给一个「一次拉全表」的接口。</li>
 * </ol>
 *
 * <p>纯单测里 {@code LambdaQueryWrapper} 需要 {@code TableInfoHelper} 的 lambda 缓存才能把
 * 方法引用解析成列名（平时由 MyBatis-Plus 注册 Mapper 时建立），因此这里手工初始化一次。
 */
class AuditLogServiceTest {

    private final OperationLogMapper operationLogMapper = mock(OperationLogMapper.class);
    private final LoginLogMapper loginLogMapper = mock(LoginLogMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, OperationLog.class);
        TableInfoHelper.initTableInfo(assistant, LoginLog.class);
    }

    private AuditLogService service() {
        return new AuditLogService(operationLogMapper, loginLogMapper, objectMapper);
    }

    private void stubEmptyPage() {
        when(operationLogMapper.selectPage(any(), any()))
                .thenReturn(new Page<OperationLog>(1, 20));
        when(loginLogMapper.selectPage(any(), any()))
                .thenReturn(new Page<LoginLog>(1, 20));
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<OperationLog> capturedWrapper() {
        ArgumentCaptor<LambdaQueryWrapper<OperationLog>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(operationLogMapper).selectPage(any(), captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private Page<OperationLog> capturedPage() {
        ArgumentCaptor<Page<OperationLog>> captor = ArgumentCaptor.forClass(Page.class);
        verify(operationLogMapper).selectPage(captor.capture(), any());
        return captor.getValue();
    }

    // ------------------------------------------------------------------
    // success 的三种取值
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("success 筛选：缺省不过滤（存量行是 NULL，不能被当成失败排除）")
    class SuccessFilter {

        @Test
        @DisplayName("缺省 -> where 里不出现 success 条件")
        void nullMeansNoFilter() {
            stubEmptyPage();

            service().query(null, null, null, null, null, null, null, null, 1, 20);

            assertThat(capturedWrapper().getSqlSegment())
                    .as("缺省时若补上 success = ? / IS NOT NULL，V51 之前的存量行会全部消失")
                    .doesNotContainIgnoringCase("success");
        }

        @Test
        @DisplayName("success=false -> 显式筛失败操作（审计最核心的问题之一）")
        void falseFiltersFailures() {
            stubEmptyPage();

            service().query(null, null, null, null, false, null, null, null, 1, 20);

            assertThat(capturedWrapper().getSqlSegment()).containsIgnoringCase("success");
        }

        @Test
        @DisplayName("success=true -> 显式筛成功操作")
        void trueFiltersSuccesses() {
            stubEmptyPage();

            service().query(null, null, null, null, true, null, null, null, 1, 20);

            assertThat(capturedWrapper().getSqlSegment()).containsIgnoringCase("success");
        }
    }

    // ------------------------------------------------------------------
    // 关键字与筛选
    // ------------------------------------------------------------------

    @Test
    @DisplayName("关键字的三处匹配必须整体加括号，否则 module 等值筛选会退化成 OR")
    void keywordOrGroupIsParenthesized() {
        stubEmptyPage();

        service().query(null, null, null, "asset", null, "delete", null, null, 1, 20);

        String segment = capturedWrapper().getSqlSegment();
        assertThat(segment)
                .as("module 的等值条件必须仍在 where 里")
                .containsIgnoringCase("module");
        assertThat(segment)
                .as("三处关键字匹配必须包在同一对括号里："
                        + "否则 module = ? OR username LIKE ? 会把别的模块的日志也捞出来")
                .containsPattern("\\([^()]*username[^()]*OR[^()]*action[^()]*OR[^()]*detail_json[^()]*\\)");
    }

    @Test
    @DisplayName("不传关键字 -> 不生成 OR 条件（避免无谓的全表 ILIKE）")
    void noKeywordMeansNoOrCondition() {
        stubEmptyPage();

        service().query(null, null, null, null, null, null, null, null, 1, 20);

        assertThat(capturedWrapper().getSqlSegment()).doesNotContainIgnoringCase("detail_json");
    }

    @Test
    @DisplayName("时间范围与 module / username / traceId 都落到 where 里")
    void appliesAllFilters() {
        stubEmptyPage();

        LocalDateTime from = LocalDateTime.of(2026, 9, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 9, 13, 23, 59);
        service().query(from, to, "admin", "asset", null, null, "trace-1", null, 1, 20);

        assertThat(capturedWrapper().getSqlSegment())
                .containsIgnoringCase("created_at")
                .containsIgnoringCase("username")
                .containsIgnoringCase("module")
                .containsIgnoringCase("trace_id");
    }

    // ------------------------------------------------------------------
    // ref_id 筛选
    // ------------------------------------------------------------------

    @Test
    @DisplayName("refId -> 等值条件落到 ref_id（「这个对象上发生过什么」不必再靠 JSON 的 LIKE）")
    void refIdFiltersByObject() {
        stubEmptyPage();

        service().query(null, null, null, null, null, null, null, 42L, 1, 20);

        assertThat(capturedWrapper().getSqlSegment()).containsIgnoringCase("ref_id");
    }

    @Test
    @DisplayName("不传 refId -> where 里不出现 ref_id 条件")
    void absentRefIdMeansNoFilter() {
        stubEmptyPage();

        service().query(null, null, null, null, null, null, null, null, 1, 20);

        assertThat(capturedWrapper().getSqlSegment()).doesNotContainIgnoringCase("ref_id");
    }

    @Test
    @DisplayName("refId 为 0 -> 当作没选，不发下去（否则会得到「空列表 + 筛选框显示已填」）")
    void zeroRefIdIsIgnored() {
        stubEmptyPage();

        service().query(null, null, null, null, null, null, null, 0L, 1, 20);

        assertThat(capturedWrapper().getSqlSegment())
                .as("主键非正不可能命中任何行；真发下去只会让页面看起来「查了但没结果」")
                .doesNotContainIgnoringCase("ref_id");
    }

    @Test
    @DisplayName("refId 为负数 -> 同样当作没选（前端手改 URL 的输入不该变成一次必然落空的查询）")
    void negativeRefIdIsIgnored() {
        stubEmptyPage();

        service().query(null, null, null, null, null, null, null, -1L, 1, 20);

        assertThat(capturedWrapper().getSqlSegment()).doesNotContainIgnoringCase("ref_id");
    }

    /**
     * 查询与索引的口径必须一致：V52 建的索引若与这里的排序/过滤维度对不上，就只是一份白占空间的
     * 数据 —— 而这一点不会报错、也不会让任何用例变红，只会在数据量涨起来之后表现为「按对象查很慢」。
     *
     * <p>读的是 classpath 上的迁移文件（迁移在 {@code src/main/resources}，测试期可用），
     * 因此不依赖工作目录，也不存在路径漂移。
     */
    @Test
    @DisplayName("V52 索引的列顺序与本次查询一致：等值列 ref_id 在前，排序列 created_at DESC, id DESC 在后")
    void indexMatchesTheQueryShape() throws Exception {
        String sql;
        try (var in = AuditLogServiceTest.class
                .getResourceAsStream("/db/migration/V52__operation_log_ref_id_index.sql")) {
            assertThat(in).as("V52 迁移必须存在，否则按对象查没有索引可用").isNotNull();
            sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        stubEmptyPage();
        service().query(null, null, null, null, null, null, null, 42L, 2, 20);

        String segment = capturedWrapper().getSqlSegment();
        assertThat(segment).as("查询按 ref_id 等值过滤，索引首列必须是 ref_id").containsIgnoringCase("ref_id");
        assertThat(sql)
                .as("索引列顺序必须与查询一致（等值列 → 排序列）；"
                        + "顺序反了不报错，只会在数据量涨起来后表现为「按对象查很慢」")
                .contains("(ref_id, created_at DESC, id DESC)");
    }

    @Test
    @DisplayName("排序必须是 created_at DESC 且带 id DESC 兜底（同秒记录否则会翻页漏行）")
    void ordersByCreatedAtThenIdDesc() {
        stubEmptyPage();

        service().query(null, null, null, null, null, null, null, null, 1, 20);

        String segment = capturedWrapper().getSqlSegment();
        assertThat(segment).containsIgnoringCase("ORDER BY");
        assertThat(segment.indexOf("created_at")).isLessThan(segment.indexOf("id"));
    }

    // ------------------------------------------------------------------
    // 分页
    // ------------------------------------------------------------------

    @Test
    @DisplayName("pageSize 超过上限 -> 收窄到 200（不设上限等于「一次拉全表」的接口）")
    void clampsPageSizeToMax() {
        stubEmptyPage();

        service().query(null, null, null, null, null, null, null, null, 1, 100_000);

        assertThat(capturedPage().getSize()).isEqualTo(AuditLogService.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("page / pageSize 非正数 -> 回到 1 / 1（负 offset 会让 SQL 报错或返回空）")
    void normalizesNonPositivePaging() {
        stubEmptyPage();

        service().query(null, null, null, null, null, null, null, null, -5, 0);

        Page<OperationLog> page = capturedPage();
        assertThat(page.getCurrent()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("返回体保留 total，前端分页器才能算总页数")
    void returnsTotal() {
        Page<OperationLog> dbPage = new Page<>(2, 20);
        dbPage.setTotal(137);
        dbPage.setRecords(List.of());
        when(operationLogMapper.selectPage(any(), any())).thenReturn(dbPage);

        PageResult<OperationLogView> result =
                service().query(null, null, null, null, null, null, null, null, 2, 20);

        assertThat(result.getTotal()).isEqualTo(137);
        assertThat(result.getPage()).isEqualTo(2);
        assertThat(result.getPageSize()).isEqualTo(20);
    }

    // ------------------------------------------------------------------
    // 视图映射
    // ------------------------------------------------------------------

    @Test
    @DisplayName("detail_json 解析为对象：前端直接渲染，不必对非法 JSON 做二次容错")
    void parsesDetailJson() {
        OperationLog row = new OperationLog();
        row.setId(1L);
        row.setDetailJson("{\"args\":[\"asset-1\"]}");
        when(operationLogMapper.selectById(any())).thenReturn(row);

        OperationLogView view = service().detail(1L);

        assertThat(view.detail()).isInstanceOf(java.util.Map.class);
    }

    @Test
    @DisplayName("detail_json 是脏数据 -> 原样以字符串返回，不让整个列表报错")
    void dirtyDetailJsonDegradesToString() {
        OperationLog row = new OperationLog();
        row.setId(1L);
        row.setDetailJson("not-json{");
        when(operationLogMapper.selectById(any())).thenReturn(row);

        assertThat(service().detail(1L).detail()).isEqualTo("not-json{");
    }

    @Test
    @DisplayName("空 detail_json -> null，而不是空对象（前端要能区分「没记录」和「记录了空」）")
    void blankDetailJsonIsNull() {
        OperationLog row = new OperationLog();
        row.setId(1L);
        row.setDetailJson("  ");
        when(operationLogMapper.selectById(any())).thenReturn(row);

        assertThat(service().detail(1L).detail()).isNull();
    }

    @Test
    @DisplayName("success / error 原样带到视图：NULL 必须保持 null（显示为「—」而非「失败」）")
    void carriesSuccessAndError() {
        OperationLog row = new OperationLog();
        row.setId(7L);
        row.setUsername("admin");
        row.setSuccess(null);
        row.setError(null);
        when(operationLogMapper.selectById(any())).thenReturn(row);

        OperationLogView view = service().detail(7L);

        assertThat(view.success()).isNull();
        assertThat(view.error()).isNull();
    }

    @Test
    @DisplayName("refId 原样带到视图；推断不出（批量操作）时保持 null，不伪造 0")
    void carriesRefId() {
        OperationLog row = new OperationLog();
        row.setId(7L);
        row.setRefId(42L);
        when(operationLogMapper.selectById(any())).thenReturn(row);
        assertThat(service().detail(7L).refId()).isEqualTo(42L);

        OperationLog bulk = new OperationLog();
        bulk.setId(8L);
        bulk.setRefId(null);
        when(operationLogMapper.selectById(any())).thenReturn(bulk);
        assertThat(service().detail(8L).refId()).isNull();
    }

    @Test
    @DisplayName("详情不存在 -> 404，不是空对象")
    void missingDetailIs404() {
        when(operationLogMapper.selectById(any())).thenReturn(null);

        assertThatThrownBy(() -> service().detail(999L)).isInstanceOf(AppException.class);
    }

    // ------------------------------------------------------------------
    // 模块清单
    // ------------------------------------------------------------------

    @Test
    @DisplayName("模块清单来自数据库现状而非硬编码：下拉里只出现确实查得到的值")
    void modulesComeFromDatabase() {
        when(operationLogMapper.selectObjs(any()))
                .thenReturn(List.<Object>of("asset", "contract"));

        assertThat(service().modules()).containsExactly("asset", "contract");
    }

    @Test
    @DisplayName("没有审计记录时模块清单为空列表，而不是报错")
    void modulesEmptyWhenNoRows() {
        when(operationLogMapper.selectObjs(any())).thenReturn(Collections.emptyList());

        assertThat(service().modules()).isEmpty();
    }

    // ------------------------------------------------------------------
    // 登录日志
    // ------------------------------------------------------------------

    @Test
    @DisplayName("登录日志 result 等值匹配小写 success / failed（AuthService 的写入口径）")
    void filtersLoginLogByResult() {
        when(loginLogMapper.selectPage(any(), any())).thenReturn(new Page<LoginLog>(1, 20));

        service().queryLoginLogs(null, null, null, "failed", null, 1, 20);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<LoginLog>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(loginLogMapper).selectPage(any(), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).containsIgnoringCase("result");
    }

    @Test
    @DisplayName("登录日志 pageSize 同样收窄，且时间范围落到 created_at")
    void loginLogsAreBounded() {
        when(loginLogMapper.selectPage(any(), any())).thenReturn(new Page<LoginLog>(1, 20));

        service().queryLoginLogs(
                LocalDateTime.of(2026, 9, 1, 0, 0), null, "admin", null, "10.0.0.1", 1, 99_999);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Page<LoginLog>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<LoginLog>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(loginLogMapper).selectPage(pageCaptor.capture(), wrapperCaptor.capture());

        assertThat(pageCaptor.getValue().getSize()).isEqualTo(AuditLogService.MAX_PAGE_SIZE);
        assertThat(wrapperCaptor.getValue().getSqlSegment())
                .containsIgnoringCase("created_at")
                .containsIgnoringCase("username")
                .containsIgnoringCase("ip");
    }

    @Test
    @DisplayName("登录日志不调 operation_log 的 Mapper（两个 Tab 各查各表，不互相拖累）")
    void loginLogQueryDoesNotTouchOperationLogMapper() {
        when(loginLogMapper.selectPage(any(), any())).thenReturn(new Page<LoginLog>(1, 20));

        service().queryLoginLogs(null, null, null, null, null, 1, 20);

        verify(operationLogMapper, never()).selectPage(any(), any());
    }
}

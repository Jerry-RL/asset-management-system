package com.ams.platform.observability.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.common.exception.AppException;
import com.ams.platform.observability.AppLog;
import com.ams.platform.observability.dto.AppLogPurgeRequest;
import com.ams.platform.observability.mapper.AppLogMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * 清理与写入的服务层测试（设计 §11、§7.3）。
 *
 * <p>最核心的一条是 {@link #purgeWithoutTimeBoundaryIsRefused()}：<strong>没有时间边界的删除
 * 等于清空全表</strong>，而日志是事后追查的唯一线索 —— 删掉之后无法证明「当时发生了什么」。
 * 因此这不是一个「参数校验」用例，而是一道安全阀。
 */
class AppLogServiceTest {

    private final AppLogMapper mapper = mock(AppLogMapper.class);
    private final AppLogAlertService alertService = mock(AppLogAlertService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 纯单测里必须手工初始化一次 TableInfo。
     *
     * <p>{@code LambdaQueryWrapper} 靠 {@code TableInfoHelper} 的缓存把方法引用（{@code AppLog::getCreatedAt}）
     * 解析成列名；该缓存平时由 MyBatis-Plus 在注册 Mapper 时建立。没有 Spring 上下文时它不存在，
     * 于是每个 lambda 都会抛「can not find lambda cache for this entity」—— 这不是生产代码的问题，
     * 而是单测需要补的一步初始化。
     */
    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AppLog.class);
    }

    private AppLogService service() {
        return new AppLogService(mapper, alertService, objectMapper);
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    @Test
    @DisplayName("写入时由服务端计算指纹并触发告警评估（客户端不可伪造指纹）")
    void computesFingerprintAndAlerts() {
        when(mapper.insert(any(AppLog.class))).thenReturn(1);

        AppLog entry = new AppLog();
        entry.setLevel("ERROR");
        entry.setAppType("h5-tenant");
        entry.setSource("js");
        entry.setMessage("boom");

        AppLog saved = service().record(entry, "at a.js:1:1");

        assertThat(saved.getFingerprint()).isNotNull().hasSize(32);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getOccurredAt()).isNotNull();
        verify(alertService).onRecord(saved);
    }

    @Test
    @DisplayName("已带指纹的记录不被覆盖（后端异常路径可能自行指定）")
    void keepsProvidedFingerprint() {
        when(mapper.insert(any(AppLog.class))).thenReturn(1);

        AppLog entry = new AppLog();
        entry.setLevel("ERROR");
        entry.setFingerprint("preset");

        assertThat(service().record(entry, null).getFingerprint()).isEqualTo("preset");
    }

    @Test
    @DisplayName("落库抛异常 -> 吞掉并返回 null（日志系统故障绝不能变成业务故障）")
    void swallowsInsertFailure() {
        when(mapper.insert(any(AppLog.class))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));

        AppLog entry = new AppLog();
        entry.setLevel("ERROR");

        assertThat(service().record(entry, null)).isNull();
        verify(alertService, never()).onRecord(any());
    }

    @Test
    @DisplayName("入参为 null 时安全返回（SDK 侧异常不该影响上报链路）")
    void nullEntryIsSafe() {
        assertThat(service().record(null, null)).isNull();
        verify(alertService, never()).onRecord(any());
    }

    // ------------------------------------------------------------------
    // 清理
    // ------------------------------------------------------------------

    @Test
    @DisplayName("【安全阀】没有时间边界 -> 拒绝执行，不删除任何行")
    void purgeWithoutTimeBoundaryIsRefused() {
        AppLogPurgeRequest request = new AppLogPurgeRequest();
        request.setBefore(null);

        assertThatThrownBy(() -> service().purge(request))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("before");

        assertThatThrownBy(() -> service().purge(null)).isInstanceOf(AppException.class);
        verify(mapper, never()).deleteByIds(any());
    }

    @Test
    @DisplayName("清理按 id 分批，且一定带上 created_at < before 条件")
    void purgeIsBatchedAndBounded() {
        AppLog row = new AppLog();
        row.setId(1L);
        when(mapper.selectList(any())).thenReturn(List.of(row));
        when(mapper.deleteByIds(any())).thenReturn(1);

        AppLogPurgeRequest request = new AppLogPurgeRequest();
        request.setBefore(java.time.LocalDateTime.now().minusDays(30));
        request.setAppType("h5-tenant");

        int deleted = service().purge(request);

        // 只取回 1 行（< 批大小 5000）=> 一轮即结束
        assertThat(deleted).isEqualTo(1);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AppLog>> captor =
                ArgumentCaptor.forClass(
                        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
                .as("清理谓词必须包含时间边界，否则就是清空全表")
                .contains("created_at");
    }

    @Test
    @DisplayName("无数据可清 -> 不调用 delete")
    void purgeWithNothingToDeleteIsNoop() {
        when(mapper.selectList(any())).thenReturn(List.of());

        AppLogPurgeRequest request = new AppLogPurgeRequest();
        request.setBefore(java.time.LocalDateTime.now().minusDays(30));

        assertThat(service().purge(request)).isZero();
        verify(mapper, never()).deleteByIds(any());
    }

    @Test
    @DisplayName("定时清理把保留天数换算成 before 边界，并限制最少 0 天")
    void purgeExpiredUsesRetentionDays() {
        when(mapper.selectList(any())).thenReturn(List.of());

        AppLogService service = service();
        assertThat(service.purgeExpired(30)).isZero();
        assertThat(service.purgeExpired(-5)).isZero();

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AppLog>> captor =
                ArgumentCaptor.forClass(
                        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        Mockito.verify(mapper, Mockito.times(2)).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("created_at");
    }
}

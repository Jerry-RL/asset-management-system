package com.ams.modules.lease.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.PageResult;
import com.ams.modules.lease.entity.Tenant;
import com.ams.modules.lease.entity.TenantCreditLog;
import com.ams.modules.lease.mapper.TenantCreditLogMapper;
import com.ams.modules.lease.mapper.TenantMapper;
import com.ams.platform.security.FieldEncryptionService;
import com.ams.platform.security.MaskingService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户/客商管理（FR-OP-001）+ 信用档案与黑名单（FR-TENANT-CREDIT-*）。
 * L4 字段（证件号）加密存储 + 盲索引；手机号展示脱敏。
 */
@Service
public class TenantService {

    private final TenantMapper tenantMapper;
    private final TenantCreditLogMapper creditLogMapper;
    private final FieldEncryptionService encryptionService;

    public TenantService(
            TenantMapper tenantMapper,
            TenantCreditLogMapper creditLogMapper,
            FieldEncryptionService encryptionService) {
        this.tenantMapper = tenantMapper;
        this.creditLogMapper = creditLogMapper;
        this.encryptionService = encryptionService;
    }

    public PageResult<Tenant> page(long page, long pageSize, String keyword, Boolean blacklist) {
        Page<Tenant> result = tenantMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<Tenant>()
                        .like(keyword != null && !keyword.isBlank(), Tenant::getName, keyword)
                        .eq(blacklist != null, Tenant::getBlacklist, blacklist)
                        .orderByDesc(Tenant::getId));
        // 脱敏展示：证件号掩码
        result.getRecords().forEach(this::maskForDisplay);
        return PageResult.of(result.getRecords(), result.getTotal(), page, pageSize);
    }

    public Tenant get(Long id) {
        Tenant tenant = tenantMapper.selectById(id);
        if (tenant == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        maskForDisplay(tenant);
        return tenant;
    }

    public Tenant create(Tenant tenant) {
        if (tenant.getIdNo() != null && !tenant.getIdNo().isBlank()) {
            tenant.setIdNoHash(FieldEncryptionService.blindIndex(tenant.getIdNo()));
            tenant.setIdNo(encryptionService.encrypt(tenant.getIdNo()));
        }
        if (tenant.getBlacklist() == null) {
            tenant.setBlacklist(false);
        }
        if (tenant.getCreditScore() == null) {
            tenant.setCreditScore(100);
        }
        tenantMapper.insert(tenant);
        return get(tenant.getId());
    }

    public Tenant update(Long id, Tenant tenant) {
        tenant.setId(id);
        if (tenant.getIdNo() != null && !tenant.getIdNo().isBlank()) {
            tenant.setIdNoHash(FieldEncryptionService.blindIndex(tenant.getIdNo()));
            tenant.setIdNo(encryptionService.encrypt(tenant.getIdNo()));
        } else {
            tenant.setIdNo(null); // 不传则不更新
        }
        tenantMapper.updateById(tenant);
        return get(id);
    }

    /** 信用评分调整 + 留痕（FR-TENANT-CREDIT-001）。 */
    @Transactional
    public void adjustCredit(Long tenantId, Integer scoreDelta, String eventType, String remark) {
        Tenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        int newScore = (tenant.getCreditScore() == null ? 100 : tenant.getCreditScore()) + scoreDelta;
        tenant.setCreditScore(Math.max(0, Math.min(200, newScore)));
        tenantMapper.updateById(tenant);

        TenantCreditLog log = new TenantCreditLog();
        log.setTenantId(tenantId);
        log.setEventType(eventType);
        log.setScoreDelta(scoreDelta);
        log.setRemark(remark);
        log.setCreatedAt(LocalDateTime.now());
        creditLogMapper.insert(log);
    }

    /** 黑名单设置（FR-TENANT-CREDIT-002）。 */
    public void setBlacklist(Long tenantId, boolean blacklist) {
        Tenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        tenant.setBlacklist(blacklist);
        tenantMapper.updateById(tenant);
    }

    /** 签约前黑名单校验（FR-TENANT-CREDIT-002）。 */
    public void assertNotBlacklisted(Long tenantId) {
        Tenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "租户不存在");
        }
        if (Boolean.TRUE.equals(tenant.getBlacklist())) {
            throw new AppException(ErrorCode.TENANT_BLACKLISTED);
        }
    }

    public List<TenantCreditLog> creditLogs(Long tenantId) {
        return creditLogMapper.selectList(
                new LambdaQueryWrapper<TenantCreditLog>()
                        .eq(TenantCreditLog::getTenantId, tenantId)
                        .orderByDesc(TenantCreditLog::getId));
    }

    private void maskForDisplay(Tenant tenant) {
        tenant.setIdNo(null); // 默认不返回证件号明文
    }
}

package com.ams.modules.intangible.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.PageResult;
import com.ams.modules.alert.entity.AlertRecord;
import com.ams.modules.alert.service.AlertService;
import com.ams.modules.intangible.entity.IntangibleAsset;
import com.ams.modules.intangible.mapper.IntangibleAssetMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 无形资产（FR-IA-*）：台账、月摊销、到期预警。
 */
@Service
public class IntangibleAssetService {

    private final IntangibleAssetMapper mapper;
    private final AlertService alertService;

    public IntangibleAssetService(IntangibleAssetMapper mapper, AlertService alertService) {
        this.mapper = mapper;
        this.alertService = alertService;
    }

    public PageResult<IntangibleAsset> page(long page, long pageSize, String keyword, String rightsType) {
        Page<IntangibleAsset> result = mapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<IntangibleAsset>()
                        .eq(rightsType != null, IntangibleAsset::getRightsType, rightsType)
                        .like(keyword != null && !keyword.isBlank(), IntangibleAsset::getName, keyword)
                        .orderByDesc(IntangibleAsset::getId));
        return PageResult.of(result.getRecords(), result.getTotal(), page, pageSize);
    }

    public IntangibleAsset get(Long id) {
        IntangibleAsset a = mapper.selectById(id);
        if (a == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return a;
    }

    public IntangibleAsset create(IntangibleAsset asset) {
        if (asset.getStatus() == null) {
            asset.setStatus("active");
        }
        if (asset.getCreatedAt() == null) {
            asset.setCreatedAt(LocalDateTime.now());
        }
        mapper.insert(asset);
        return asset;
    }

    public IntangibleAsset update(Long id, IntangibleAsset asset) {
        asset.setId(id);
        mapper.updateById(asset);
        return get(id);
    }

    public void delete(Long id) {
        mapper.deleteById(id);
    }

    /**
     * 月摊销：按 amortizationRule 解析年限（如 "10y" / "120m"），否则默认 10 年直线摊销。
     */
    @Transactional
    public int amortizeMonthly() {
        List<IntangibleAsset> list = mapper.selectList(
                new LambdaQueryWrapper<IntangibleAsset>()
                        .eq(IntangibleAsset::getStatus, "active")
                        .isNotNull(IntangibleAsset::getNetValue)
                        .gt(IntangibleAsset::getNetValue, BigDecimal.ZERO));
        int n = 0;
        for (IntangibleAsset a : list) {
            int months = parseMonths(a.getAmortizationRule());
            BigDecimal original = a.getOriginalValue() == null ? a.getNetValue() : a.getOriginalValue();
            if (original == null || original.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal monthly = original.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
            BigDecimal next = a.getNetValue().subtract(monthly).max(BigDecimal.ZERO);
            a.setNetValue(next);
            if (next.compareTo(BigDecimal.ZERO) <= 0) {
                a.setStatus("amortized");
            }
            mapper.updateById(a);
            n++;
        }
        return n;
    }

    /** 到期扫描：N 日内到期 → 预警。 */
    @Transactional
    public int scanExpiry(int withinDays) {
        LocalDate threshold = LocalDate.now().plusDays(withinDays);
        List<IntangibleAsset> list = mapper.selectList(
                new LambdaQueryWrapper<IntangibleAsset>()
                        .eq(IntangibleAsset::getStatus, "active")
                        .isNotNull(IntangibleAsset::getExpiryDate)
                        .le(IntangibleAsset::getExpiryDate, threshold));
        int n = 0;
        for (IntangibleAsset a : list) {
            boolean expired = !a.getExpiryDate().isAfter(LocalDate.now());
            AlertRecord r = new AlertRecord();
            r.setAlertType("intangible_expiry");
            r.setSubType(expired ? "expired" : "expiring");
            r.setLevel(expired ? 3 : 2);
            r.setBizType("intangible");
            r.setBizId(a.getId());
            r.setTitle(expired ? "无形资产已到期" : "无形资产即将到期");
            r.setContent(a.getName() + "（" + a.getAssetNo() + "）到期日 " + a.getExpiryDate());
            alertService.trigger(r);
            if (expired) {
                a.setStatus("expired");
                mapper.updateById(a);
            }
            n++;
        }
        return n;
    }

    public Map<String, Object> summary() {
        List<IntangibleAsset> list = mapper.selectList(null);
        Map<String, Long> byType = new HashMap<>();
        long expiring = 0;
        LocalDate threshold = LocalDate.now().plusDays(90);
        BigDecimal net = BigDecimal.ZERO;
        for (IntangibleAsset a : list) {
            String t = a.getRightsType() == null ? "other" : a.getRightsType();
            byType.merge(t, 1L, Long::sum);
            net = net.add(a.getNetValue() == null ? BigDecimal.ZERO : a.getNetValue());
            if (a.getExpiryDate() != null && !a.getExpiryDate().isAfter(threshold)
                    && "active".equals(a.getStatus())) {
                expiring++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", list.size());
        result.put("netValue", net);
        result.put("byRightsType", byType);
        result.put("expiringWithin90Days", expiring);
        return result;
    }

    private static int parseMonths(String rule) {
        if (rule == null || rule.isBlank()) {
            return 120;
        }
        String r = rule.trim().toLowerCase();
        try {
            if (r.endsWith("y")) {
                return Integer.parseInt(r.replace("y", "").trim()) * 12;
            }
            if (r.endsWith("m")) {
                return Integer.parseInt(r.replace("m", "").trim());
            }
            return Integer.parseInt(r);
        } catch (Exception e) {
            return 120;
        }
    }
}

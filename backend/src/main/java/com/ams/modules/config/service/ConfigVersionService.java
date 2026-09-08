package com.ams.modules.config.service;

import com.ams.modules.config.entity.ConfigVersion;
import com.ams.modules.config.mapper.ConfigVersionMapper;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 参数配置版本（FR-CFG-002）：生效日期 + 版本快照 + 回滚审计。
 */
@Service
public class ConfigVersionService {

    public static final String KEY_VACATE_POOL_ORDER = "vacate.fund_pool_order";
    public static final String DEFAULT_VACATE_POOL_ORDER =
            "damage,penalty,rent,utility,late_fee,deposit,prepay";
    public static final String KEY_EARLY_TERMINATE_MONTHS = "contract.early_terminate_penalty_months";

    private final ConfigVersionMapper configVersionMapper;

    public ConfigVersionService(ConfigVersionMapper configVersionMapper) {
        this.configVersionMapper = configVersionMapper;
    }

    @Transactional
    public ConfigVersion change(String configKey, String newValue, LocalDate effectiveDate) {
        if (effectiveDate == null) {
            effectiveDate = LocalDate.now();
        }
        ConfigVersion current = effectiveVersion(configKey, LocalDate.now());
        int nextVersion = nextVersion(configKey);

        ConfigVersion version = new ConfigVersion();
        version.setConfigKey(configKey);
        version.setConfigValue(newValue);
        version.setEffectiveDate(effectiveDate);
        version.setVersion(nextVersion);
        version.setOperatorId(SecurityUtils.currentUserIdOrNull());
        version.setOldValue(current == null ? null : current.getConfigValue());
        version.setNewValue(newValue);
        version.setCreatedAt(LocalDateTime.now());
        configVersionMapper.insert(version);
        return version;
    }

    public ConfigVersion effectiveVersion(String configKey, LocalDate asOfDate) {
        LocalDate date = asOfDate == null ? LocalDate.now() : asOfDate;
        return configVersionMapper.selectOne(
                new LambdaQueryWrapper<ConfigVersion>()
                        .eq(ConfigVersion::getConfigKey, configKey)
                        .le(ConfigVersion::getEffectiveDate, date)
                        .orderByDesc(ConfigVersion::getEffectiveDate)
                        .orderByDesc(ConfigVersion::getVersion)
                        .last("LIMIT 1"));
    }

    public String getValue(String configKey, String defaultValue) {
        ConfigVersion v = effectiveVersion(configKey, LocalDate.now());
        if (v == null || v.getConfigValue() == null || v.getConfigValue().isBlank()) {
            return defaultValue;
        }
        return v.getConfigValue();
    }

    public Map<String, Object> snapshot(LocalDate asOfDate) {
        LocalDate date = asOfDate == null ? LocalDate.now() : asOfDate;
        List<ConfigVersion> all = configVersionMapper.selectList(
                new LambdaQueryWrapper<ConfigVersion>()
                        .le(ConfigVersion::getEffectiveDate, date)
                        .orderByDesc(ConfigVersion::getEffectiveDate)
                        .orderByDesc(ConfigVersion::getVersion));
        Map<String, Object> map = new LinkedHashMap<>();
        for (ConfigVersion v : all) {
            map.putIfAbsent(v.getConfigKey(), Map.of(
                    "value", v.getConfigValue() == null ? "" : v.getConfigValue(),
                    "version", v.getVersion(),
                    "effectiveDate", String.valueOf(v.getEffectiveDate()),
                    "id", v.getId()));
        }
        map.putIfAbsent(KEY_VACATE_POOL_ORDER, Map.of(
                "value", DEFAULT_VACATE_POOL_ORDER,
                "version", 0,
                "effectiveDate", String.valueOf(date),
                "builtin", true));
        return map;
    }

    public List<ConfigVersion> history(String configKey) {
        return configVersionMapper.selectList(
                new LambdaQueryWrapper<ConfigVersion>()
                        .eq(configKey != null, ConfigVersion::getConfigKey, configKey)
                        .orderByDesc(ConfigVersion::getId));
    }

    private int nextVersion(String configKey) {
        ConfigVersion latest = configVersionMapper.selectOne(
                new LambdaQueryWrapper<ConfigVersion>()
                        .eq(ConfigVersion::getConfigKey, configKey)
                        .orderByDesc(ConfigVersion::getVersion)
                        .last("LIMIT 1"));
        return latest == null ? 1 : latest.getVersion() + 1;
    }
}

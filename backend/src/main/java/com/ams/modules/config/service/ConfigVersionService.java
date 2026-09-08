package com.ams.modules.config.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.config.entity.ConfigVersion;
import com.ams.modules.config.mapper.ConfigVersionMapper;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 参数配置版本（FR-CFG-002）：生效日期 + 版本快照 + 回滚审计。
 * 历史账单/发票/滞纳金计息引用创建时快照，不被新版本污染。
 */
@Service
public class ConfigVersionService {

    private final ConfigVersionMapper configVersionMapper;

    public ConfigVersionService(ConfigVersionMapper configVersionMapper) {
        this.configVersionMapper = configVersionMapper;
    }

    /** 参数变更：旧版本失效，插入新版本（生效日、旧值、新值、操作人）。 */
    @Transactional
    public ConfigVersion change(String configKey, String newValue, LocalDate effectiveDate) {
        if (effectiveDate == null) {
            effectiveDate = LocalDate.now();
        }
        // 当前生效版本
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

    /** 查询任意时点生效版本（FR-CFG-002 snapshot）。 */
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

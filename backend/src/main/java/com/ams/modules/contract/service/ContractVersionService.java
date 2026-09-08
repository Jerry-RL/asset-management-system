package com.ams.modules.contract.service;

import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.entity.ContractVersion;
import com.ams.modules.contract.mapper.ContractVersionMapper;
import com.ams.platform.security.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 合同版本快照（FR-CON-LC-006）。
 */
@Service
public class ContractVersionService {

    private static final Logger log = LoggerFactory.getLogger(ContractVersionService.class);

    private final ContractVersionMapper versionMapper;
    private final ObjectMapper objectMapper;

    public ContractVersionService(ContractVersionMapper versionMapper, ObjectMapper objectMapper) {
        this.versionMapper = versionMapper;
        this.objectMapper = objectMapper;
    }

    public void snapshot(Contract before, Contract after, String changeType) {
        ContractVersion ver = new ContractVersion();
        ver.setContractId(after != null ? after.getId() : (before == null ? null : before.getId()));
        ver.setVersion(after != null && after.getVersion() != null ? after.getVersion()
                : (before == null || before.getVersion() == null ? 1 : before.getVersion()));
        ver.setChangeType(changeType);
        ver.setBeforeJson(toJson(before));
        ver.setAfterJson(toJson(after));
        ver.setOperatorId(SecurityUtils.currentUserIdOrNull());
        ver.setCreatedAt(LocalDateTime.now());
        versionMapper.insert(ver);
    }

    private String toJson(Contract contract) {
        if (contract == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(contract);
        } catch (Exception e) {
            log.warn("contract version serialize failed: {}", e.getMessage());
            return String.valueOf(contract.getId());
        }
    }
}

package com.ams.modules.contract.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.contract.ContractStatus;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.modules.lease.entity.Tenant;
import com.ams.modules.lease.mapper.TenantMapper;
import com.ams.platform.integration.esign.ESignAdapter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 电子签约（FR-ESIGN）：发起签署、回调完成、查询状态。
 */
@Service
public class ESignService {

    public static final String STATUS_NONE = "none";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_SIGNED = "signed";
    public static final String STATUS_FAILED = "failed";

    private final ContractMapper contractMapper;
    private final TenantMapper tenantMapper;
    private final ESignAdapter eSignAdapter;

    public ESignService(ContractMapper contractMapper, TenantMapper tenantMapper, ESignAdapter eSignAdapter) {
        this.contractMapper = contractMapper;
        this.tenantMapper = tenantMapper;
        this.eSignAdapter = eSignAdapter;
    }

    @Transactional
    public Map<String, Object> start(Long contractId) {
        Contract contract = require(contractId);
        if (ContractStatus.VOIDED.equals(contract.getStatus())
                || ContractStatus.TERMINATED.equals(contract.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "合同已终止，无法签署");
        }
        if (STATUS_SIGNED.equals(contract.getEsignStatus())) {
            return statusMap(contract, null);
        }
        Tenant tenant = contract.getTenantId() == null ? null : tenantMapper.selectById(contract.getTenantId());
        String name = tenant == null ? "签署人" : tenant.getName();
        String phone = tenant == null ? "" : tenant.getPhone();
        ESignAdapter.SignSession session = eSignAdapter.createSignFlow(
                contract.getId(), contract.getContractNo(), name, phone);
        contract.setEsignStatus(STATUS_PENDING);
        contract.setEsignFlowId(session.flowId());
        contractMapper.updateById(contract);

        Map<String, Object> result = statusMap(contract, session.signUrl());
        result.put("mock", session.mock());
        // Mock：创建后可立即完成（便于联调）；真实环境等回调
        if (session.mock()) {
            completeByFlowId(session.flowId(), null);
            Contract refreshed = require(contractId);
            return statusMap(refreshed, session.signUrl());
        }
        return result;
    }

    @Transactional
    public Contract handleCallback(String flowId, String status, Long evidenceFileId) {
        if (flowId == null || flowId.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "缺少 flowId");
        }
        if ("signed".equalsIgnoreCase(status) || "completed".equalsIgnoreCase(status) || status == null) {
            return completeByFlowId(flowId, evidenceFileId);
        }
        Contract contract = findByFlowId(flowId);
        contract.setEsignStatus(STATUS_FAILED);
        contractMapper.updateById(contract);
        return contract;
    }

    public Map<String, Object> status(Long contractId) {
        return statusMap(require(contractId), null);
    }

    private Contract completeByFlowId(String flowId, Long evidenceFileId) {
        Contract contract = findByFlowId(flowId);
        contract.setEsignStatus(STATUS_SIGNED);
        contract.setEsignSignedAt(LocalDateTime.now());
        if (evidenceFileId != null) {
            contract.setEsignEvidenceFileId(evidenceFileId);
        }
        contractMapper.updateById(contract);
        return contract;
    }

    private Contract findByFlowId(String flowId) {
        Contract contract = contractMapper.selectOne(
                new LambdaQueryWrapper<Contract>().eq(Contract::getEsignFlowId, flowId).last("LIMIT 1"));
        if (contract == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "签署流程不存在");
        }
        return contract;
    }

    private Contract require(Long id) {
        Contract contract = contractMapper.selectById(id);
        if (contract == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "合同不存在");
        }
        return contract;
    }

    private Map<String, Object> statusMap(Contract contract, String signUrl) {
        Map<String, Object> map = new HashMap<>();
        map.put("contractId", contract.getId());
        map.put("contractNo", contract.getContractNo());
        map.put("esignStatus", contract.getEsignStatus() == null ? STATUS_NONE : contract.getEsignStatus());
        map.put("esignFlowId", contract.getEsignFlowId());
        map.put("esignSignedAt", contract.getEsignSignedAt());
        map.put("esignEvidenceFileId", contract.getEsignEvidenceFileId());
        if (signUrl != null) {
            map.put("signUrl", signUrl);
        }
        return map;
    }
}

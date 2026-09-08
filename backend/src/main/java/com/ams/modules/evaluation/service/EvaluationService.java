package com.ams.modules.evaluation.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.evaluation.entity.EvaluationRequest;
import com.ams.modules.evaluation.mapper.EvaluationRequestMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评估申请与定价基准闭环（FR-EVAL-*）：评估申请 → 报告 → 定价基准回写 → 合规校验引用。
 */
@Service
public class EvaluationService {

    private final EvaluationRequestMapper evaluationRequestMapper;
    private final AssetMapper assetMapper;

    public EvaluationService(EvaluationRequestMapper evaluationRequestMapper, AssetMapper assetMapper) {
        this.evaluationRequestMapper = evaluationRequestMapper;
        this.assetMapper = assetMapper;
    }

    public List<EvaluationRequest> list(Long assetId) {
        return evaluationRequestMapper.selectList(
                new LambdaQueryWrapper<EvaluationRequest>()
                        .eq(assetId != null, EvaluationRequest::getAssetId, assetId)
                        .orderByDesc(EvaluationRequest::getId));
    }

    public EvaluationRequest apply(EvaluationRequest request) {
        request.setStatus("applying");
        evaluationRequestMapper.insert(request);
        return request;
    }

    @Transactional
    public EvaluationRequest accept(Long id) {
        EvaluationRequest request = require(id);
        request.setStatus("accepted");
        evaluationRequestMapper.updateById(request);
        return request;
    }

    @Transactional
    public EvaluationRequest startEvaluating(Long id) {
        EvaluationRequest request = require(id);
        request.setStatus("evaluating");
        evaluationRequestMapper.updateById(request);
        return request;
    }

    /**
     * 结果回写（FR-EVAL-003/004）：评估价写入 base_rent_assessed；
     * 招租/备案目的同步抬高底价，供低价签约与挂牌引用。
     */
    @Transactional
    public EvaluationRequest recordResult(Long id, BigDecimal resultValue, Long reportFileId, Boolean syncFloor) {
        EvaluationRequest request = require(id);
        if (resultValue == null || resultValue.compareTo(BigDecimal.ZERO) <= 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "评估价须大于 0");
        }
        request.setStatus("reported");
        request.setResultValue(resultValue);
        if (reportFileId != null) {
            request.setReportFileId(reportFileId);
        }
        evaluationRequestMapper.updateById(request);

        if (request.getAssetId() != null) {
            Asset asset = assetMapper.selectById(request.getAssetId());
            if (asset != null) {
                asset.setBaseRentAssessed(resultValue);
                boolean doSync = syncFloor == null
                        ? ("lease".equals(request.getPurpose()) || "filing".equals(request.getPurpose()))
                        : syncFloor;
                if (doSync) {
                    BigDecimal floor = asset.getBaseRentFloor();
                    if (floor == null || floor.compareTo(resultValue) < 0) {
                        asset.setBaseRentFloor(resultValue);
                    }
                }
                assetMapper.updateById(asset);
            }
        }
        return request;
    }

    /** 有效底价：max(备案/挂牌底价, 评估价)，供签约与招租校验（FR-EVAL-004）。 */
    public static BigDecimal effectiveFloor(Asset asset) {
        if (asset == null) {
            return null;
        }
        BigDecimal floor = asset.getBaseRentFloor();
        BigDecimal assessed = asset.getBaseRentAssessed();
        if (floor == null) {
            return assessed;
        }
        if (assessed == null) {
            return floor;
        }
        return floor.max(assessed);
    }

    private EvaluationRequest require(Long id) {
        EvaluationRequest request = evaluationRequestMapper.selectById(id);
        if (request == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return request;
    }
}

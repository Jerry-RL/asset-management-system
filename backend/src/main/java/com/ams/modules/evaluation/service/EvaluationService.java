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

    /** 结果回写（FR-EVAL-003）：评估价写入资产 base_rent_assessed。 */
    @Transactional
    public EvaluationRequest recordResult(Long id, BigDecimal resultValue) {
        EvaluationRequest request = evaluationRequestMapper.selectById(id);
        if (request == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        request.setStatus("reported");
        request.setResultValue(resultValue);
        evaluationRequestMapper.updateById(request);

        if (request.getAssetId() != null) {
            Asset asset = assetMapper.selectById(request.getAssetId());
            if (asset != null) {
                asset.setBaseRentAssessed(resultValue);
                assetMapper.updateById(asset);
            }
        }
        return request;
    }
}

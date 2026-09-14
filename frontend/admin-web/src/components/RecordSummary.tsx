import { Descriptions, Empty, Tag } from 'antd';
import {
  countCostItems,
  formatValidRange,
  formatWan,
  isEvaluationExpired,
  latestEvaluation,
  sumCostAmountWan,
} from '@/lib/recordSummary';
import type { CostRecord, EvaluationInfo } from '@/lib/recordSheet';

// ============================================================================
// 成本 / 评估只读汇总（分区详情页顶部）。
//
// 数据来源就是宿主页已经拉回来的 recordSheet（`loadOwnerSheet`），**不新增接口**：
// 汇总与下方可编辑的 Tab 读同一份 state，因此录入过程中顶部的数字会即时跟着变，
// 不会出现「保存后汇总才对」的割裂感；反过来它也只是展示，不回写任何值。
//
// 组件刻意与卡片解耦（宿主自己包 Card），资产表单 / 项目表单将来要加同样的汇总时直接复用；
// 合计 / 取最新 / 过期判定这些口径都在 `lib/recordSummary.ts`，这里只负责排版。
// ============================================================================

export interface RecordSummaryProps {
  costRecords?: CostRecord[] | null;
  evaluations?: EvaluationInfo[] | null;
}

export function RecordSummary({ costRecords, evaluations }: RecordSummaryProps) {
  const costs = costRecords ?? [];
  const evaluationsRows = evaluations ?? [];

  // 两条都为空时不铺一屏「—」（那是噪音而不是信息），直接给空态
  if (costs.length === 0 && evaluationsRows.length === 0) {
    return (
      <Empty
        image={Empty.PRESENTED_IMAGE_SIMPLE}
        description="暂无成本与评估记录"
        className="py-2"
      />
    );
  }

  const latest = latestEvaluation(evaluationsRows);
  const totalCostWan = sumCostAmountWan(costs);

  return (
    <Descriptions column={2} size="small">
      <Descriptions.Item label="成本记录">{costs.length} 笔</Descriptions.Item>
      <Descriptions.Item label="成本金额合计">
        <span className="tabular-nums">{formatWan(totalCostWan)}</span>
        {totalCostWan != null && <span className="text-xs text-gray-400 ml-1">万元</span>}
      </Descriptions.Item>
      <Descriptions.Item label="费用明细">{countCostItems(costs)} 项</Descriptions.Item>
      <Descriptions.Item label="评估记录">{evaluationsRows.length} 次</Descriptions.Item>
      <Descriptions.Item label="最新评估机构">{latest?.institution || '—'}</Descriptions.Item>
      <Descriptions.Item label="评估资产价值">
        <span className="tabular-nums">{formatWan(latest?.assetValue)}</span>
      </Descriptions.Item>
      <Descriptions.Item label="最新租赁单价">
        <span className="tabular-nums">{formatWan(latest?.rentUnitPrice)}</span>
      </Descriptions.Item>
      <Descriptions.Item label="最新租赁价格">
        <span className="tabular-nums">{formatWan(latest?.rentPrice)}</span>
      </Descriptions.Item>
      <Descriptions.Item label="评估时间">{latest?.evaluateDate || '—'}</Descriptions.Item>
      <Descriptions.Item label="评估有效期限">
        {formatValidRange(latest?.validFrom, latest?.validTo)}
        {/* 过期只是提示：本期不做「无有效评估则禁止签约」这类联动 */}
        {isEvaluationExpired(latest) && (
          <Tag color="error" className="ml-2">
            已过期
          </Tag>
        )}
      </Descriptions.Item>
    </Descriptions>
  );
}

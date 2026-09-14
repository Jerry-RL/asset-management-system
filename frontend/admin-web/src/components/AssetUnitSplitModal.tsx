import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Input, InputNumber, Modal, Space, Tag } from 'antd';
import { DeleteOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { LEASE_CONTROL_STATUS } from '@/lib/labels';
import type { AssetUnit } from '@/lib/assetUnits';

/**
 * 面积守恒容差，与后端 `AssetUnitService.TOLERANCE` 同值（0.005）。
 *
 * <p>刻意用 0.005 而不是 0.01：面积都是 2 位小数，合法输入的和与源面积应当**精确相等**，
 * 容差只为吸收浮点噪声。放成 0.01 会让「3 × 33.33 = 99.99 拆 100.00」这种丢 0.01 的输入
 * 被放过，而它恰恰会让拆分后的面积合计与原面积不符。
 */
const AREA_TOLERANCE = 0.005;

const round2 = (value: number) => Math.round(value * 100) / 100;

const formatArea = (value: unknown) =>
  Number(value ?? 0).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });

export interface AssetUnitSplitModalProps {
  /** null = 关闭；否则为待拆分的单元 */
  unit: AssetUnit | null;
  /** 提交中：驱动 confirmLoading，避免重复提交产生两批子单元 */
  submitting: boolean;
  onCancel: () => void;
  /** 校验通过后回调；提交与报错由调用方负责 */
  onSubmit: (childAreas: number[], remark: string) => void;
}

/**
 * 单元拆分弹窗（ADR-0019 决策 A1 / ADR-0021 规则 S1–S12）。
 *
 * <p>「拆分」在业务上就是**把一间拆成几间**，因此这里只让人填"每间的面积"，
 * 不提供"拆成几份"这种间接输入 —— 面积列表的长度即份数，也正好对应后端的入参形状。
 *
 * <p>三条前端校验（**只用于提前反馈，正确性以后端为准**）：
 * <ol>
 *   <li>至少 2 份、每份大于 0；</li>
 *   <li>合计等于原单元面积（容差见 {@link AREA_TOLERANCE}）；</li>
 *   <li>原单元面积须大于 0 —— 面积为 0 的是"面积待补"的占位单元，后端一律拒绝拆分。</li>
 * </ol>
 */
export function AssetUnitSplitModal({
  unit,
  submitting,
  onCancel,
  onSubmit,
}: AssetUnitSplitModalProps) {
  const sourceArea = Number(unit?.area ?? 0);
  const [areas, setAreas] = useState<number[]>([]);
  const [remark, setRemark] = useState('');

  /**
   * 打开时重置为"均分 2 份"。
   *
   * <p>默认均分而不是留空：一是「一间拆两间」是最常见场景，二是均分天然满足面积守恒，
   * 使用者一进来就能直接确认，不必先算数。末份用"总额减前份"而不是各算一半，
   * 避免 2 位小数四舍五入后合计差 0.01。
   */
  useEffect(() => {
    if (!unit) return;
    const half = round2(sourceArea / 2);
    setAreas([half, round2(sourceArea - half)]);
    setRemark('');
  }, [unit, sourceArea]);

  const sum = useMemo(
    () => round2(areas.reduce((acc, a) => acc + (Number.isFinite(a) ? a : 0), 0)),
    [areas],
  );
  const diff = round2(sourceArea - sum);
  const allPositive = areas.every((a) => Number.isFinite(a) && a > 0);
  const hasPlaceholder = sourceArea <= 0;
  const valid =
    !hasPlaceholder && areas.length >= 2 && allPositive && Math.abs(diff) <= AREA_TOLERANCE;

  const handleChange = (index: number, value: number | null) => {
    setAreas((prev) =>
      prev.map((item, i) => (i === index ? (value == null ? 0 : value) : item)),
    );
  };

  /** 新增一份：把"尚未分配的剩余面积"整份放进去，加完即重新满足面积守恒。 */
  const handleAdd = () => {
    setAreas((prev) => {
      const rest = round2(sourceArea - prev.reduce((acc, a) => acc + (Number.isFinite(a) ? a : 0), 0));
      return [...prev, rest > 0 ? rest : 0];
    });
  };

  const handleRemove = (index: number) => {
    setAreas((prev) => prev.filter((_, i) => i !== index));
  };

  const handleReset = () => {
    setAreas([round2(sourceArea / 2), round2(sourceArea - round2(sourceArea / 2))]);
  };

  /** 校验通过才回调；不通过时下方已给出具体原因，这里静默返回 */
  const handleOk = () => {
    if (!valid) return;
    onSubmit(areas, remark);
  };

  return (
    <Modal
      title="拆分计租单元"
      open={!!unit}
      onCancel={onCancel}
      onOk={handleOk}
      confirmLoading={submitting}
      okText="确认拆分"
      okButtonProps={{ disabled: !valid }}
      width={Math.min(560, typeof window !== 'undefined' ? window.innerWidth - 32 : 560)}
    >
      {unit && (
        <div className="space-y-3">
          <div className="rounded-md bg-gray-50 px-3 py-2 text-sm">
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
              <span className="font-medium">{unit.unitNo}</span>
              <span className="text-gray-600">{unit.unitName || '—'}</span>
              <Tag>{LEASE_CONTROL_STATUS[String(unit.unitStatus ?? '')] ?? '—'}</Tag>
            </div>
            <div className="text-xs text-gray-500 mt-1 tabular-nums">
              原单元面积 {formatArea(unit.area)} ㎡
              {unit.rentableArea != null && ` · 可租 ${formatArea(unit.rentableArea)} ㎡`}
              {unit.baseRent != null && ` · 底价 ${formatArea(unit.baseRent)}`}
            </div>
          </div>

          {hasPlaceholder && (
            <Alert
              type="warning"
              showIcon
              message="该单元面积为 0，无法拆分"
              description="面积为 0 表示「面积待补」的占位单元，拆分它既无法校验面积守恒，也会生成一批 0 面积单元。请先在资产表单中补齐该资产的租赁面积。"
            />
          )}

          {/* 结构变更不可逆：拆错了只能靠「合并」回滚，且仅在子单元被下游引用之前有效 */}
          <Alert
            type="info"
            showIcon
            message="拆分后原单元将失效"
            description="原单元会被软删除（历史身份保留在拆合日志中），子单元各自独立计租。拆分后如需还原，用「合并」把子单元合并回来，但一旦有合同或招租引用了子单元就无法回滚。"
          />

          <div>
            <div className="flex items-center justify-between mb-2">
              <span className="text-sm font-medium">子单元面积（㎡）</span>
              <Space size={4}>
                <Button size="small" icon={<ReloadOutlined />} onClick={handleReset}>
                  均分
                </Button>
                <Button size="small" icon={<PlusOutlined />} onClick={handleAdd}>
                  添加一项
                </Button>
              </Space>
            </div>

            <div className="space-y-2">
              {areas.map((area, index) => (
                <div key={`split-area-${index}`} className="flex items-center gap-2">
                  <span className="text-xs text-gray-500 w-12 shrink-0 tabular-nums">
                    第 {index + 1} 份
                  </span>
                  <InputNumber
                    className="flex-1 min-w-0"
                    value={area}
                    min={0}
                    precision={2}
                    placeholder="面积"
                    onChange={(value) => handleChange(index, value as number | null)}
                  />
                  <Button
                    size="small"
                    type="text"
                    danger
                    icon={<DeleteOutlined />}
                    // 至少保留 2 份：少于 2 份就不构成"拆分"
                    disabled={areas.length <= 2}
                    aria-label={`删除第 ${index + 1} 份`}
                    onClick={() => handleRemove(index)}
                  />
                </div>
              ))}
            </div>

            <div className="flex flex-wrap items-center gap-x-4 gap-y-1 mt-2 text-xs tabular-nums">
              <span className={allPositive ? 'text-gray-500' : 'text-red-500'}>
                {allPositive ? '合计' : '每份面积须大于 0，合计'} {formatArea(sum)} ㎡
              </span>
              {!hasPlaceholder && Math.abs(diff) > AREA_TOLERANCE && (
                <span className="text-red-500">
                  与原面积相差 {formatArea(Math.abs(diff))} ㎡，须相等
                </span>
              )}
              {valid && <span className="text-green-600">面积守恒，可提交</span>}
            </div>
          </div>

          <div>
            <div className="text-sm font-medium mb-1">备注</div>
            <Input.TextArea
              rows={2}
              value={remark}
              placeholder="如：按 1F 商铺分间改造拆分"
              onChange={(e) => setRemark(e.target.value)}
            />
          </div>
        </div>
      )}
    </Modal>
  );
}

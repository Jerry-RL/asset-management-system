import type { FieldConfig } from '@/components/ResourcePage';
import * as L from '@/lib/labels';

/**
 * 发布招租表单字段（FR-LEASE-001/002，V59）。
 *
 * <p>三处共用同一份定义，避免「列表行内发布 / 招租管理新增 / 资产租赁管理发布」
 * 三套表单各自漂移：
 * <ol>
 *   <li>资产台账行内快捷操作「发布招租」（{@link withAsset} = false，资产由行注入）；</li>
 *   <li>「招租管理」新增弹窗（需要选择资产）；</li>
 *   <li>「资产租赁管理」的发布弹窗（从行内进时不选资产，从工具栏进时选择）。</li>
 * </ol>
 *
 * <p><b>资产选择只列空置资产</b>：后端 `submit` 会 `assertVacant`，列出在租 / 自用的资产
 * 只会让用户填完表单才被驳回。`pageSize=200` 与「快速生成合同」的租户下拉同口径。
 *
 * @param withAsset 是否包含「资产」选择项；从资产列表行内发起时传 false
 *                  （此时 assetId 由行数据注入，不应让用户再选一次）
 */
export const listingPublishFields = ({ withAsset }: { withAsset: boolean }): FieldConfig[] => [
  ...(withAsset
    ? ([
        {
          name: 'assetId',
          label: '资产',
          type: 'select',
          required: true,
          optionsPath: '/assets?page=1&pageSize=200&leaseControlStatus=vacant',
          optionsValueKey: 'id',
          optionsLabelKey: 'name',
          optionsLabelExtraKey: 'assetNo',
        },
      ] satisfies FieldConfig[])
    : []),
  {
    name: 'coverImage',
    label: '封面图',
    type: 'image',
    bizType: 'lease_listing',
  },
  {
    name: 'detailImages',
    label: '详情列表图',
    type: 'images',
    bizType: 'lease_listing',
    maxCount: 9,
  },
  {
    name: 'rentType',
    label: '租金类型',
    type: 'select',
    options: Object.entries(L.RENT_TYPE).map(([value, label]) => ({ value, label })),
  },
  {
    name: 'annualRent',
    label: '年租金(元)',
    type: 'number',
    required: true,
  },
  {
    name: 'rentNegotiable',
    label: '可议价（低于评估底价年化额时须勾选）',
    type: 'boolean',
  },
  {
    name: 'recommended',
    label: '设为推荐（小程序端优先展示）',
    type: 'boolean',
  },
  {
    name: 'sortNo',
    label: '排序号（越小越前）',
    type: 'number',
  },
  { name: 'intro', label: '介绍', type: 'textarea' },
  { name: 'remark', label: '招租说明', type: 'textarea' },
];

/** 发布招租的默认值：租金类型默认「固定年租」（表单收的是年租金）。 */
export const LISTING_PUBLISH_DEFAULTS = {
  rentType: 'fixed_yearly',
  rentNegotiable: false,
  recommended: false,
  sortNo: 0,
  intro: '',
  remark: '',
} as const;

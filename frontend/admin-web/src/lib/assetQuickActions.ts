import type { RowActionConfig } from '@/components/ResourcePage';
import * as L from '@/lib/labels';

/** 资产台账 / 资产档案共用的快捷操作 */
export const ASSET_QUICK_ACTIONS: RowActionConfig[] = [
  {
    key: 'createContract',
    label: '快速生成合同',
    title: '快速生成合同',
    submitPath: '/contracts',
    injectIdField: 'assetId',
    omitFields: ['templateId'],
    successMessage: '合同草稿已创建',
    visible: (row) => {
      const s = String(row.leaseControlStatus ?? '');
      // 非自用：排除自用/已退出/处置中；含空置、招租中、在租、部分出租等
      return s !== 'self_use' && s !== 'exited' && s !== 'disposing';
    },
    defaultValues: (row) => {
      const today = new Date();
      const end = new Date(today);
      end.setFullYear(end.getFullYear() + 1);
      const fmt = (d: Date) => d.toISOString().slice(0, 10);
      return {
        tenantId: undefined,
        startDate: fmt(today),
        endDate: fmt(end),
        rentType: 'fixed_monthly',
        paymentCycle: 'monthly',
        rentAmount: row.baseRentFloor ?? row.baseRentAssessed ?? row.marketRefRent ?? undefined,
        depositAmount: undefined,
        leaseArea: row.area ?? undefined,
        templateId: undefined,
        requestSpecialApproval: false,
        remark: '',
      };
    },
    fields: [
      {
        name: 'tenantId',
        label: '租户',
        type: 'select',
        required: true,
        optionsPath: '/tenants?page=1&pageSize=200',
        optionsValueKey: 'id',
        optionsLabelKey: 'name',
        optionsLabelExtraKey: 'phone',
      },
      { name: 'startDate', label: '起租日', type: 'date', required: true },
      { name: 'endDate', label: '到期日', type: 'date', required: true },
      {
        name: 'rentType',
        label: '租金类型',
        type: 'select',
        options: Object.entries(L.RENT_TYPE).map(([value, label]) => ({ value, label })),
      },
      {
        name: 'paymentCycle',
        label: '缴费周期',
        type: 'select',
        options: Object.entries(L.PAYMENT_CYCLE).map(([value, label]) => ({ value, label })),
      },
      { name: 'rentAmount', label: '周期租金(元)', type: 'number', required: true },
      { name: 'depositAmount', label: '保证金(元)', type: 'number' },
      { name: 'leaseArea', label: '租赁面积(㎡)', type: 'number' },
      {
        name: 'templateId',
        label: '合同模板（可选，选后自动生成 Word）',
        type: 'select',
        optionsPath: '/contract-templates?page=1&pageSize=50&enabled=true',
        optionsValueKey: 'id',
        optionsLabelKey: 'name',
        optionsLabelExtraKey: 'templateCode',
      },
      {
        name: 'requestSpecialApproval',
        label: '低于底价走特批',
        type: 'boolean',
      },
      { name: 'remark', label: '备注', type: 'textarea' },
    ],
    followUp: {
      when: (values) => values.templateId != null && values.templateId !== '',
      path: (created) => `/contracts/${created.id}/document/generate`,
      body: (_created, values) => ({ templateId: values.templateId }),
      successMessage: '合同已创建并生成 Word 文档',
    },
  },
  {
    key: 'publishListing',
    label: '发布招租',
    title: '快速发布招租信息',
    submitPath: '/lease-listings',
    injectIdField: 'assetId',
    successMessage: '招租已发布',
    visible: (row) => {
      const s = String(row.leaseControlStatus ?? '');
      return s === 'vacant' || s === '';
    },
    defaultValues: (row) => ({
      rentAmount: row.baseRentFloor ?? row.baseRentAssessed ?? row.marketRefRent ?? undefined,
      rentNegotiable: false,
      remark: '',
    }),
    fields: [
      { name: 'rentAmount', label: '挂牌租金(元)', type: 'number', required: true },
      {
        name: 'rentNegotiable',
        label: '可议价',
        type: 'boolean',
      },
      { name: 'remark', label: '招租说明', type: 'textarea' },
    ],
  },
  {
    key: 'createRepair',
    label: '新增报修',
    title: '新增报修工单',
    submitPath: '/repairs',
    injectIdField: 'assetId',
    successMessage: '报修工单已创建',
    defaultValues: () => ({
      reporterName: '',
      reporterPhone: '',
      description: '',
    }),
    fields: [
      { name: 'description', label: '报修说明', type: 'textarea', required: true },
      { name: 'reporterName', label: '报修人' },
      { name: 'reporterPhone', label: '联系电话' },
    ],
  },
  {
    key: 'createInspection',
    label: '登记巡查',
    title: '登记巡查记录',
    submitPath: '/inspections',
    injectIdField: 'assetId',
    successMessage: '巡查记录已登记',
    defaultValues: () => ({
      planDate: new Date().toISOString().slice(0, 10),
      result: '正常',
      hazardDesc: '',
    }),
    fields: [
      { name: 'planDate', label: '计划/巡查日', type: 'date', required: true },
      { name: 'result', label: '巡查结果', required: true },
      { name: 'hazardDesc', label: '隐患说明', type: 'textarea' },
    ],
  },
];

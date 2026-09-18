import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  StopOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { useListQuery } from '@/lib/listQuery';
import { confirmDelete } from '@/lib/confirm';
import { TableActions, actionsColumnWidth } from '@/components/TableActions';
import {
  assetOperatorApi,
  OPERATOR_STATUS,
  OPERATOR_STATUS_OPTIONS,
  SCOPE_TYPE_OPTIONS,
  scopeCountSummary,
  scopeLabel,
  scopeOptionLabel,
  scopeTypeLabel,
  type OperatorInput,
  type OperatorRoleOption,
  type OperatorScopeOption,
  type OperatorUserOption,
  type OperatorView,
} from '@/lib/assetOperator';

const formatTime = (value?: string | null): string =>
  value ? value.slice(0, 19).replace('T', ' ') : '-';

/** 表单里的范围行：带上标的名用于编辑回显（提交时会剔掉） */
interface ScopeFormValue {
  scopeType?: string;
  scopeId?: number;
  scopeName?: string | null;
  parentName?: string | null;
}

/** 运营范围候选每页条数：够用且不会把下拉撑爆（后端另有 200 的硬上限）。 */
const SCOPE_PAGE_SIZE = 50;

/**
 * 运营范围行：一个「类型 + 标的」的组合。
 *
 * <p>两个下拉**联动**：候选查询在后端按 `scopeType + companyId` 分派（项目按 `company_id`、
 * 分区经项目、资产经 `asset_company_id`），因此类型或公司一变就必须重拉候选 ——
 * 只靠搜索框是拉不到的。
 *
 * <p>类型变化时**清掉已选标的**：`project:3` 与 `asset:3` 是两个完全不同的对象，
 * 不清空会让「项目 #3」在切成资产后静默变成「资产 #3」，用户看到的是另一条记录。
 */
function ScopeRow({
  value,
  companyId,
  onChange,
  onRemove,
  removable,
}: {
  value: ScopeFormValue;
  companyId?: number | null;
  onChange: (patch: Partial<ScopeFormValue>) => void;
  onRemove: () => void;
  removable: boolean;
}) {
  const [options, setOptions] = useState<OperatorScopeOption[]>([]);
  const [loading, setLoading] = useState(false);

  const scopeType = value.scopeType;
  const scopeId = value.scopeId;

  const fetchOptions = useCallback(
    async (keyword: string) => {
      if (!scopeType || !companyId) {
        setOptions([]);
        return;
      }
      setLoading(true);
      try {
        const data = await assetOperatorApi.scopeOptions({
          scopeType,
          companyId,
          keyword: keyword || undefined,
          page: 1,
          pageSize: SCOPE_PAGE_SIZE,
        });
        setOptions(data.list);
      } catch (e) {
        setOptions([]);
        message.error(e instanceof Error ? e.message : '加载运营范围候选失败');
      } finally {
        setLoading(false);
      }
    },
    [scopeType, companyId],
  );

  // 类型 / 公司变化：候选集整体变了，必须重拉一次（只靠搜索框是拉不到的）
  useEffect(() => {
    void fetchOptions('');
  }, [fetchOptions]);

  /**
   * 候选 + 当前值。
   *
   * <p>编辑态下已选标的可能不在第一页候选里，不并进去，`Select` 会因为 options 里
   * 找不到而只显示一个裸数字 —— 用户以为选错了，就去重选一次。
   */
  const selectOptions = useMemo(() => {
    const base = options.map((option) => ({
      value: option.scopeId,
      label: scopeOptionLabel(option),
    }));
    if (scopeId != null && !base.some((option) => option.value === scopeId)) {
      const label = value.parentName
        ? `${value.parentName} / ${value.scopeName ?? `#${scopeId}`}`
        : (value.scopeName ?? `#${scopeId}`);
      base.unshift({ value: scopeId, label });
    }
    return base;
  }, [options, scopeId, value.scopeName, value.parentName]);

  const disabled = !companyId;

  return (
    <div className="flex items-start gap-2">
      <Select
        value={scopeType}
        placeholder="类型"
        style={{ width: 110 }}
        options={SCOPE_TYPE_OPTIONS}
        aria-label="范围类型"
        onChange={(next) =>
          // 切类型必须清空标的：两类对象的 id 空间完全不同，留着就是错挂
          onChange({ scopeType: next, scopeId: undefined, scopeName: null, parentName: null })
        }
      />
      <Select
        className="flex-1"
        value={scopeId}
        placeholder={disabled ? '请先选择人员（需要其所属公司）' : '搜索并选择标的'}
        showSearch
        allowClear
        disabled={disabled}
        loading={loading}
        // 必须 false：否则 antd 会在本地对 options 做过滤，与远程搜索结果叠加后
        // 表现为「搜到的项搜不到、没搜到的项一直在」
        filterOption={false}
        onSearch={(keyword) => void fetchOptions(keyword)}
        options={selectOptions}
        aria-label="运营范围标的"
        onChange={(next: number | undefined) => {
          const hit = options.find((option) => option.scopeId === next);
          onChange({
            scopeId: next,
            scopeName: hit?.name ?? null,
            parentName: hit?.parentName ?? null,
          });
        }}
      />
      <Button
        danger
        type="text"
        icon={<DeleteOutlined />}
        disabled={!removable}
        aria-label="删除该范围"
        onClick={onRemove}
      />
    </div>
  );
}

/**
 * 资产运营人员管理（V60）。
 *
 * <p>列表 + 弹窗表单：新增/编辑时填 **人员选择 / 角色选择 / 资产运营范围选择**。
 * 范围是 `Form.List` 式的可增删行，支持「项目 / 分区 / 资产」混选。
 *
 * <p><b>为什么不做成 `ResourcePage` 配置</b>：范围是「类型 + 标的」的联动多行，
 * 且标的下拉必须带公司做远程搜索 —— 超出 `FieldConfig` 的表达能力（同 V55 / V56 / V57 的取舍）。
 *
 * <p><b>范围候选的公司从哪来</b>：取自**所选人员**的所属公司（人员下拉会带回 `companyId`）。
 * 不加一个独立的公司选择框：运营范围的意义就是「这个人在自己公司下负责什么」，
 * 让人手工再选一次公司只会制造「选的人和范围不属于同一家公司」这种脏组合。
 * 人员没有所属公司时明确提示并禁用范围选择，而不是退化成一个不筛选的候选列表。
 */
export function AssetOperatorsPage() {
  const { page, pageSize, keyword, filters, setPage, setPageSize, setKeyword, setFilter } =
    useListQuery({ filterKeys: ['status'], defaultPageSize: 10 });
  const status = filters.status || '';

  const [rows, setRows] = useState<OperatorView[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);

  const [form] = Form.useForm();
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [saving, setSaving] = useState(false);
  /** 所选人员的所属公司：范围候选的过滤条件（见类注释） */
  const [personCompanyId, setPersonCompanyId] = useState<number | null>(null);
  const [personOptions, setPersonOptions] = useState<OperatorUserOption[]>([]);
  const [personLoading, setPersonLoading] = useState(false);
  const [roleOptions, setRoleOptions] = useState<OperatorRoleOption[]>([]);

  const [detail, setDetail] = useState<OperatorView | null>(null);

  const scopes = Form.useWatch<ScopeFormValue[]>('scopes', form) ?? [];

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await assetOperatorApi.list({
        page,
        pageSize,
        keyword: keyword || undefined,
        status: status === '' ? undefined : Number(status),
      });
      setRows(data.list);
      setTotal(data.total);
    } catch (e) {
      // 失败时清空而不是留着上一次的数据：留着会让人以为「筛选没生效」
      setRows([]);
      setTotal(0);
      message.error(e instanceof Error ? e.message : '加载资产运营人员失败');
    } finally {
      setLoading(false);
    }
  }, [page, pageSize, keyword, status]);

  useEffect(() => {
    void load();
  }, [load]);

  const searchPersons = useCallback(async (kw: string) => {
    setPersonLoading(true);
    try {
      const data = await assetOperatorApi.userOptions({
        keyword: kw || undefined,
        page: 1,
        // 一屏候选够选即可，真正的定位靠搜索
        pageSize: 50,
      });
      setPersonOptions(data.list);
    } catch (e) {
      setPersonOptions([]);
      message.error(e instanceof Error ? e.message : '加载人员失败');
    } finally {
      setPersonLoading(false);
    }
  }, []);

  const loadRoleOptions = useCallback(async () => {
    try {
      setRoleOptions(await assetOperatorApi.roleOptions());
    } catch (e) {
      setRoleOptions([]);
      message.error(e instanceof Error ? e.message : '加载角色失败');
    }
  }, []);

  useEffect(() => {
    void loadRoleOptions();
  }, [loadRoleOptions]);

  const personSelectOptions = useMemo(() => {
    const base = personOptions.map((option) => ({
      value: option.userId,
      label: option.phone ? `${option.name}（${option.phone}）` : String(option.name ?? ''),
    }));
    // 编辑态：已选人员可能不在首页候选里，并进去避免 Select 只显示一个裸数字
    const current = form.getFieldValue('userId') as number | undefined;
    const currentName = form.getFieldValue('userLabel') as string | undefined;
    if (current != null && currentName && !base.some((option) => option.value === current)) {
      base.unshift({ value: current, label: currentName });
    }
    return base;
    // form.getFieldValue 不是响应式来源，这里靠 modalOpen / 候选变化驱动重算
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [personOptions, modalOpen]);

  const openCreate = useCallback(() => {
    setEditingId(null);
    setPersonCompanyId(null);
    setModalOpen(true);
    form.resetFields();
    form.setFieldsValue({
      status: 1,
      roleIds: [],
      // 默认给一行空范围：新建立刻就能看到「要填什么」，而不是先点一次「添加范围」
      scopes: [{ scopeType: 'project' }],
    });
    void searchPersons('');
  }, [form, searchPersons]);

  const openEdit = useCallback(
    async (id: number) => {
      try {
        // 必须先取详情：列表不返回 scopes 明细（避免一页塞进上百个范围对象）
        const data = await assetOperatorApi.detail(id);
        setEditingId(id);
        setPersonCompanyId(data.companyId ?? null);
        setPersonOptions([
          {
            userId: data.userId,
            name: data.userName,
            phone: data.phone,
            companyId: data.companyId,
            companyName: data.companyName,
            departmentId: data.departmentId,
            departmentName: data.departmentName,
          },
        ]);
        form.resetFields();
        form.setFieldsValue({
          userId: data.userId,
          userLabel: data.userName ? `${data.userName}（${data.phone ?? '-'}）` : undefined,
          roleIds: data.roleIds ?? [],
          status: data.status,
          remark: data.remark ?? '',
          scopes: (data.scopes ?? []).map((scope) => ({
            scopeType: scope.scopeType,
            scopeId: scope.scopeId,
            scopeName: scope.scopeName,
            parentName: scope.parentName,
          })),
        });
        setModalOpen(true);
      } catch (e) {
        message.error(e instanceof Error ? e.message : '加载详情失败');
      }
    },
    [form],
  );

  const handleSubmit = async () => {
    let values: {
      userId?: number;
      roleIds?: number[];
      status?: number;
      remark?: string;
      scopes?: ScopeFormValue[];
    };
    try {
      values = await form.validateFields();
    } catch {
      // 校验失败由表单内联提示，这里不额外弹 toast
      return;
    }
    const scopeRows = values.scopes ?? [];
    const emptyRow = scopeRows.find((row) => !row.scopeType || row.scopeId == null);
    if (emptyRow) {
      message.error('每个运营范围都要选好类型与标的（空行请删除）');
      return;
    }
    if (scopeRows.length === 0) {
      message.error('请至少选择一个资产运营范围');
      return;
    }
    const payload: OperatorInput = {
      userId: values.userId as number,
      roleIds: values.roleIds ?? [],
      // 只提交二元组：scopeName / parentName 是展示用的原料，后端不接收
      scopes: scopeRows.map((row) => ({
        scopeType: String(row.scopeType),
        scopeId: row.scopeId as number,
      })),
      status: values.status,
      remark: values.remark?.trim() || null,
    };
    setSaving(true);
    try {
      if (editingId == null) {
        await assetOperatorApi.create(payload);
        message.success('资产运营人员已新增');
      } else {
        await assetOperatorApi.update(editingId, payload);
        message.success('资产运营人员已更新');
      }
      setModalOpen(false);
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const handleToggleStatus = useCallback(
    async (row: OperatorView) => {
      const next = row.status === 1 ? 0 : 1;
      try {
        await assetOperatorApi.updateStatus(row.id, next);
        message.success(next === 1 ? '已启用' : '已停用');
        await load();
      } catch (e) {
        message.error(e instanceof Error ? e.message : '操作失败');
      }
    },
    [load],
  );

  const handleDelete = useCallback(
    (row: OperatorView) => {
      confirmDelete({
        name: row.userName ?? undefined,
        resourceLabel: '资产运营人员',
        onOk: async () => {
          try {
            await assetOperatorApi.remove(row.id);
            message.success('已删除');
            await load();
          } catch (e) {
            message.error(e instanceof Error ? e.message : '删除失败');
            throw e;
          }
        },
      });
    },
    [load],
  );

  const handleOpenDetail = useCallback(async (id: number) => {
    try {
      setDetail(await assetOperatorApi.detail(id));
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载详情失败');
    }
  }, []);

  const columns = useMemo<ColumnsType<OperatorView>>(
    () => [
      {
        title: '人员',
        dataIndex: 'userName',
        width: 160,
        render: (value: string, row) => (
          <div>
            <div className="font-medium">{value ?? '-'}</div>
            <div className="text-xs text-gray-500">{row.phone ?? '-'}</div>
          </div>
        ),
      },
      { title: '所属公司', dataIndex: 'companyName', width: 160, ellipsis: true },
      { title: '所属部门', dataIndex: 'departmentName', width: 140, ellipsis: true },
      {
        title: '角色',
        dataIndex: 'roleNames',
        render: (value: string[]) =>
          value && value.length > 0 ? (
            <Space size={[4, 4]} wrap>
              {value.map((name) => (
                <Tag key={name} className="m-0">
                  {name}
                </Tag>
              ))}
            </Space>
          ) : (
            '-'
          ),
      },
      {
        title: '资产运营范围',
        key: 'scopes',
        width: 220,
        render: (_: unknown, row) => (
          <Space size={[4, 4]} wrap>
            {row.projectCount > 0 && <Tag color="blue">项目 {row.projectCount}</Tag>}
            {row.zoneCount > 0 && <Tag color="cyan">分区 {row.zoneCount}</Tag>}
            {row.assetCount > 0 && <Tag color="geekblue">资产 {row.assetCount}</Tag>}
            {row.projectCount + row.zoneCount + row.assetCount === 0 && '-'}
          </Space>
        ),
      },
      {
        title: '状态',
        dataIndex: 'status',
        width: 90,
        render: (value: number) => (
          <Tag color={value === 1 ? 'success' : 'default'}>
            {OPERATOR_STATUS[String(value)] ?? value}
          </Tag>
        ),
      },
      {
        title: '登记时间',
        dataIndex: 'createdAt',
        width: 170,
        render: (value: string) => formatTime(value),
      },
      {
        title: '操作',
        key: 'actions',
        fixed: 'right',
        width: actionsColumnWidth(['详情', '编辑', '停用', '删除']),
        render: (_: unknown, row: OperatorView) => (
          <TableActions
            actions={[
              {
                key: 'detail',
                label: '详情',
                icon: <EyeOutlined />,
                onClick: () => void handleOpenDetail(row.id),
              },
              {
                key: 'edit',
                label: '编辑',
                icon: <EditOutlined />,
                onClick: () => void openEdit(row.id),
              },
              {
                key: 'toggle',
                label: row.status === 1 ? '停用' : '启用',
                icon: row.status === 1 ? <StopOutlined /> : <ThunderboltOutlined />,
                onClick: () => void handleToggleStatus(row),
              },
              {
                key: 'delete',
                label: '删除',
                icon: <DeleteOutlined />,
                danger: true,
                onClick: () => handleDelete(row),
              },
            ]}
            max={2}
          />
        ),
      },
    ],
    [handleDelete, handleOpenDetail, handleToggleStatus, openEdit],
  );

  return (
    <div className="p-4 space-y-4">
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <div>
          <h1 className="text-xl font-semibold m-0">资产运营人员管理</h1>
          <p className="text-gray-500 text-sm m-0 mt-1">
            登记运营人员及其角色、资产运营范围（项目 / 分区 / 资产）
          </p>
        </div>
        <Space>
          <Input
            allowClear
            value={keyword}
            prefix={<SearchOutlined />}
            placeholder="姓名 / 手机 / 项目 / 资产"
            style={{ width: 240 }}
            aria-label="关键字"
            onChange={(e) => setKeyword(e.target.value)}
          />
          <Select
            allowClear
            value={status === '' ? undefined : Number(status)}
            placeholder="状态"
            style={{ width: 110 }}
            options={OPERATOR_STATUS_OPTIONS}
            aria-label="状态"
            onChange={(value) => setFilter('status', value === undefined ? '' : String(value))}
          />
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新增运营人员
          </Button>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
        </Space>
      </div>

      <Card size="small">
        <Table
          rowKey="id"
          loading={loading}
          dataSource={rows}
          columns={columns}
          scroll={{ x: 1400 }}
          locale={{
            emptyText: (
              <Empty description="暂无资产运营人员。点「新增运营人员」开始登记（需要先在人员维护里有人）" />
            ),
          }}
          pagination={{
            current: page,
            pageSize,
            total,
            showSizeChanger: true,
            showTotal: (count) => `共 ${count} 条`,
            onChange: (nextPage, nextSize) => {
              if (nextSize !== pageSize) setPageSize(nextSize);
              else setPage(nextPage);
            },
          }}
        />
      </Card>

      <Modal
        open={modalOpen}
        title={editingId == null ? '新增资产运营人员' : '编辑资产运营人员'}
        width={760}
        okText="保存"
        cancelText="取消"
        confirmLoading={saving}
        onOk={handleSubmit}
        onCancel={() => setModalOpen(false)}
        destroyOnHidden
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="userId"
            label="人员"
            rules={[{ required: true, message: '请选择人员' }]}
            extra="仅显示启用状态的账号；候选来自本模块接口，不要求人员维护权限"
          >
            <Select
              showSearch
              filterOption={false}
              placeholder="输入姓名 / 手机 / 账号搜索"
              loading={personLoading}
              onSearch={(kw) => void searchPersons(kw)}
              options={personSelectOptions}
              aria-label="人员"
              onChange={(value: number) => {
                const hit = personOptions.find((option) => option.userId === value);
                setPersonCompanyId(hit?.companyId ?? null);
                form.setFieldValue('userLabel', hit?.name);
              }}
            />
          </Form.Item>

          <Form.Item
            name="roleIds"
            label="角色"
            rules={[{ required: true, message: '请至少选择一个角色' }]}
            extra="仅登记到运营人员档案（不会改动该账号的实际角色授权）"
          >
            <Select
              mode="multiple"
              placeholder="请选择角色"
              optionFilterProp="label"
              options={roleOptions.map((role) => ({
                value: role.roleId,
                label: role.name,
              }))}
              aria-label="角色"
            />
          </Form.Item>

          {!personCompanyId && (
            <Alert
              type="warning"
              showIcon
              className="mb-3"
              message="所选人员未设置「所属公司」，无法筛选运营范围候选"
              description="运营范围按人员所属公司收敛（项目按所属公司、分区与资产经项目 / 资产公司推导）。请先在「组织架构 → 人员维护」里为该人员设置所属公司。"
            />
          )}

          <Form.Item label="资产运营范围" required>
            <div className="space-y-2">
              {scopes.map((scope, index) => (
                <ScopeRow
                  // 范围行没有稳定 id（可增删、类型可切换），用下标做 key：
                  // 行内状态只有两个受控下拉，重建一行不会丢用户已填的内容
                  key={index}
                  value={scope}
                  companyId={personCompanyId}
                  removable={scopes.length > 1}
                  onChange={(patch) => {
                    const next = scopes.map((item, i) =>
                      i === index ? { ...item, ...patch } : item,
                    );
                    form.setFieldValue('scopes', next);
                  }}
                  onRemove={() => {
                    form.setFieldValue(
                      'scopes',
                      scopes.filter((_, i) => i !== index),
                    );
                  }}
                />
              ))}
              <Button
                type="dashed"
                block
                icon={<PlusOutlined />}
                onClick={() => form.setFieldValue('scopes', [...scopes, { scopeType: 'project' }])}
              >
                添加范围
              </Button>
              <div className="text-xs text-gray-500">
                项目 / 分区 / 资产可混选；同一份档案里同一标的只需登记一次。
              </div>
            </div>
          </Form.Item>

          <Form.Item name="status" label="状态">
            <Select options={OPERATOR_STATUS_OPTIONS} aria-label="状态" />
          </Form.Item>

          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} placeholder="选填" />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        open={detail != null}
        title="资产运营人员详情"
        width={640}
        onClose={() => setDetail(null)}
        destroyOnHidden
      >
        {detail && (
          <div className="space-y-4">
            <Descriptions title="人员" size="small" column={2} bordered>
              <Descriptions.Item label="姓名">{detail.userName ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="手机号">{detail.phone ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="所属公司">{detail.companyName ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="所属部门">{detail.departmentName ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={detail.status === 1 ? 'success' : 'default'}>
                  {OPERATOR_STATUS[String(detail.status)] ?? detail.status}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="登记时间">{formatTime(detail.createdAt)}</Descriptions.Item>
              <Descriptions.Item label="备注" span={2}>
                {detail.remark || '-'}
              </Descriptions.Item>
            </Descriptions>

            <Descriptions title="角色" size="small" column={1} bordered>
              <Descriptions.Item label="角色">
                {detail.roleNames.length > 0 ? (
                  <Space size={[4, 4]} wrap>
                    {detail.roleNames.map((name) => (
                      <Tag key={name} className="m-0">
                        {name}
                      </Tag>
                    ))}
                  </Space>
                ) : (
                  '-'
                )}
              </Descriptions.Item>
            </Descriptions>

            <Descriptions
              title={`资产运营范围（${scopeCountSummary(detail)}）`}
              size="small"
              column={1}
              bordered
            >
              <Descriptions.Item label="范围">
                {detail.scopes && detail.scopes.length > 0 ? (
                  <Space size={[4, 4]} wrap>
                    {detail.scopes.map((scope) => (
                      <Tooltip
                        key={`${scope.scopeType}-${scope.scopeId}`}
                        title={scopeLabel(scope)}
                      >
                        <Tag color="blue" className="m-0 max-w-[260px] truncate">
                          {scopeTypeLabel(scope.scopeType)} · {scopeLabel(scope)}
                        </Tag>
                      </Tooltip>
                    ))}
                  </Space>
                ) : (
                  '-'
                )}
              </Descriptions.Item>
            </Descriptions>

            {detail.scopes &&
              detail.scopes.some((scope) => !scope.parentName && !scope.scopeName) && (
                <Alert
                  type="warning"
                  showIcon
                  message="有范围标的已不存在"
                  description="标的名显示为「已删除标的 #id」时，表示该标的已被删除或数据不一致，建议编辑本档案换成有效标的。"
                />
              )}
          </div>
        )}
      </Drawer>
    </div>
  );
}

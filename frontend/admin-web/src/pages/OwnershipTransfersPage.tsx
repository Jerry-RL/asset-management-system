import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button,
  Descriptions,
  Drawer,
  Empty,
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
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  SendOutlined,
  SwapOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { useListQuery } from '@/lib/listQuery';
import { usePerm } from '@/lib/perm';
import { loadCompanies, type CompanyOption } from '@/lib/org';
import {
  TRANSFER_DIRECTION,
  TRANSFER_MODE,
  TRANSFER_SCOPE,
  OWNERSHIP_TRANSFER_STATUS,
} from '@/lib/labels';
import {
  assetOptionLabel,
  companyLabel,
  ownershipTransferApi,
  type OwnershipTransferView,
} from '@/lib/ownershipTransfer';

const CODE = 'deed.ownershipTransfer';

const STATUS_COLOR: Record<string, string> = { draft: 'default', completed: 'green' };

const formatTime = (value?: string | null): string =>
  value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-';

/**
 * 权属流转列表页。
 *
 * 筛选项刻意只有四个（状态 / 方向 / 原公司 / 关键字）：一张流转单本身没有那么多维度，
 * 堆满筛选框会让「先按状态看一眼草稿」这个最常用的动作变慢。
 *
 * 单据没有单号列（与处置单/调拨一致），界面用 `#id` 指代 —— 加一个业务单号需要
 * 编号规则、年度重置与重号检测，而它并不解决任何现有问题。
 */
export function OwnershipTransfersPage() {
  const navigate = useNavigate();
  const can = usePerm();
  const canCreate = can(CODE, 'create');
  const canUpdate = can(CODE, 'update');
  const canDelete = can(CODE, 'delete');

  const list = useListQuery({
    filterKeys: ['status', 'direction', 'fromCompanyId'],
    defaultPageSize: 10,
  });
  const { page, pageSize, keyword, filters, setPage, setPageSize, setKeyword, setFilter } = list;

  const status = filters.status || undefined;
  const direction = filters.direction || undefined;
  const fromCompanyId = filters.fromCompanyId ? Number(filters.fromCompanyId) : undefined;

  const [companies, setCompanies] = useState<CompanyOption[]>([]);
  const [rows, setRows] = useState<OwnershipTransferView[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [detail, setDetail] = useState<OwnershipTransferView | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [working, setWorking] = useState(false);

  useEffect(() => {
    void loadCompanies().then(setCompanies);
  }, []);

  const companyOptions = useMemo(
    () => companies.map((company) => ({ value: company.id, label: company.name })),
    [companies],
  );

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await ownershipTransferApi.list({
        page,
        pageSize,
        status,
        direction,
        fromCompanyId,
        keyword: keyword || undefined,
      });
      setRows(data.list);
      setTotal(data.total);
    } catch (e) {
      // 失败时清空而不是留着上一次的数据：留着会让人以为「筛选没生效」
      setRows([]);
      setTotal(0);
      message.error(e instanceof Error ? e.message : '加载权属流转单失败');
    } finally {
      setLoading(false);
    }
  }, [page, pageSize, status, direction, fromCompanyId, keyword]);

  useEffect(() => {
    void load();
  }, [load]);

  const handleOpenDetail = useCallback(async (id: number) => {
    setDetailLoading(true);
    setDetail(null);
    try {
      const data = await ownershipTransferApi.detail(id);
      setDetail(data);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载详情失败');
    } finally {
      setDetailLoading(false);
    }
  }, []);

  /**
   * 生效：不可逆的跨法人变更，必须二次确认。
   *
   * 确认文案里写明「会改写 N 个资产的产权 / 经营公司」而不是笼统的「确认生效？」——
   * 用户需要在这一刻知道影响面，而不是事后再去对账。
   */
  const handleEffect = useCallback(
    (row: OwnershipTransferView) => {
      Modal.confirm({
        title: '确认生效？',
        content: (
          <div className="text-sm">
            <div>
              将把 <b>{row.assetCount}</b> 个资产的
              <b>{TRANSFER_SCOPE[row.transferScope] ?? row.transferScope}</b>
              改写为「{companyLabel(row.toCompanyName, row.toCompanyId)}」。
            </div>
            <div className="text-amber-600 mt-1">
              {row.direction === 'external'
                ? '外部流转：资产会被标记为「已对外转出」并置为已退出，且不可撤销。'
                : '生效后不可撤销，需要再改回来只能新建一张反向单。'}
            </div>
          </div>
        ),
        okText: '确认生效',
        cancelText: '取消',
        onOk: async () => {
          setWorking(true);
          try {
            await ownershipTransferApi.effect(row.id);
            message.success('已生效');
            await load();
          } catch (e) {
            message.error(e instanceof Error ? e.message : '生效失败');
            throw e;
          } finally {
            setWorking(false);
          }
        },
      });
    },
    [load],
  );

  const handleDelete = useCallback(
    (row: OwnershipTransferView) => {
      Modal.confirm({
        title: '确认删除该草稿？',
        content: '删除后单据不再出现在列表与资产档案中；已完成的单据不能删除。',
        okText: '删除',
        okButtonProps: { danger: true },
        cancelText: '取消',
        onOk: async () => {
          setWorking(true);
          try {
            await ownershipTransferApi.remove(row.id);
            message.success('已删除');
            await load();
          } catch (e) {
            message.error(e instanceof Error ? e.message : '删除失败');
            throw e;
          } finally {
            setWorking(false);
          }
        },
      });
    },
    [load],
  );

  const columns: ColumnsType<OwnershipTransferView> = useMemo(
    () => [
      {
        title: '单据',
        dataIndex: 'id',
        key: 'id',
        width: 90,
        render: (_, row) => <span className="tabular-nums">#{row.id}</span>,
      },
      {
        title: '流转方向',
        dataIndex: 'direction',
        key: 'direction',
        width: 100,
        render: (_, row) => (
          <Tag color={row.direction === 'external' ? 'orange' : 'blue'} className="m-0">
            {TRANSFER_DIRECTION[row.direction] ?? row.direction}
          </Tag>
        ),
      },
      {
        title: '权属类型',
        dataIndex: 'transferScope',
        key: 'transferScope',
        width: 120,
        render: (_, row) => TRANSFER_SCOPE[row.transferScope] ?? row.transferScope,
      },
      {
        title: '原公司 → 新公司',
        key: 'companies',
        width: 260,
        render: (_, row) => (
          <span className="text-gray-800">
            {companyLabel(row.fromCompanyName, row.fromCompanyId)}
            <span className="text-gray-400 mx-1">→</span>
            <b>{companyLabel(row.toCompanyName, row.toCompanyId)}</b>
          </span>
        ),
      },
      {
        title: '流转类型',
        dataIndex: 'transferMode',
        key: 'transferMode',
        width: 110,
        render: (_, row) => TRANSFER_MODE[row.transferMode] ?? row.transferMode,
      },
      {
        title: '变更申请人',
        dataIndex: 'applicantName',
        key: 'applicantName',
        width: 120,
      },
      {
        title: '金额(万元)',
        dataIndex: 'amountWan',
        key: 'amountWan',
        width: 110,
        align: 'right',
        render: (_, row) =>
          row.amountWan === null || row.amountWan === undefined ? (
            <span className="text-gray-300">-</span>
          ) : (
            <span className="tabular-nums">{row.amountWan}</span>
          ),
      },
      {
        title: '资产数',
        dataIndex: 'assetCount',
        key: 'assetCount',
        width: 90,
        align: 'right',
        render: (_, row) => <span className="tabular-nums">{row.assetCount}</span>,
      },
      {
        title: '状态',
        dataIndex: 'status',
        key: 'status',
        width: 90,
        render: (_, row) => (
          <Tag color={STATUS_COLOR[row.status] ?? 'default'} className="m-0">
            {OWNERSHIP_TRANSFER_STATUS[row.status] ?? row.status}
          </Tag>
        ),
      },
      {
        title: '创建时间',
        dataIndex: 'createdAt',
        key: 'createdAt',
        width: 150,
        render: (_, row) => (
          <span className="tabular-nums text-gray-600">{formatTime(row.createdAt)}</span>
        ),
      },
      {
        title: '操作',
        key: 'actions',
        width: 170,
        fixed: 'right',
        render: (_, row) => (
          <Space size={4}>
            <Button
              type="link"
              size="small"
              className="p-0"
              onClick={() => void handleOpenDetail(row.id)}
            >
              详情
            </Button>
            {/* 草稿才可编辑 / 生效 / 删除 —— 与后端状态机一致，避免点了才报 409 */}
            {row.status === 'draft' && canUpdate && (
              <>
                <Button
                  type="link"
                  size="small"
                  className="p-0"
                  icon={<EditOutlined />}
                  onClick={() => navigate(`/ownership-transfers/${row.id}/edit`)}
                >
                  编辑
                </Button>
                <Button
                  type="link"
                  size="small"
                  className="p-0"
                  icon={<SendOutlined />}
                  disabled={working}
                  onClick={() => handleEffect(row)}
                >
                  生效
                </Button>
              </>
            )}
            {row.status === 'draft' && canDelete && (
              <Button
                type="link"
                size="small"
                danger
                className="p-0"
                icon={<DeleteOutlined />}
                aria-label={`删除权属流转单 #${row.id}`}
                disabled={working}
                onClick={() => handleDelete(row)}
              />
            )}
          </Space>
        ),
      },
    ],
    [canUpdate, canDelete, working, navigate, handleOpenDetail, handleEffect, handleDelete],
  );

  return (
    <div className="space-y-4 min-w-0 max-w-full overflow-hidden">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-lg font-semibold text-gray-900 flex items-center gap-2 m-0">
            <SwapOutlined className="text-[var(--ams-primary)]" />
            权属流转
          </h1>
          <p className="text-sm text-gray-500 mt-1 mb-0">
            一次为多个资产变更产权公司 / 经营公司；草稿可反复修改，生效后不可撤销
          </p>
        </div>
        <Space wrap size={[8, 8]}>
          <Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>
            刷新
          </Button>
          {canCreate && (
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => navigate('/ownership-transfers/new')}
            >
              新建流转单
            </Button>
          )}
        </Space>
      </div>

      <div className="bg-white rounded-xl border border-[var(--ams-border)] p-3 sm:p-4">
        <div className="flex flex-wrap items-center gap-2 mb-3">
          <Select
            className="!w-[130px]"
            allowClear
            placeholder="全部状态"
            value={status}
            options={[
              { value: 'draft', label: OWNERSHIP_TRANSFER_STATUS.draft },
              { value: 'completed', label: OWNERSHIP_TRANSFER_STATUS.completed },
            ]}
            onChange={(value?: string) => setFilter('status', value ?? '')}
          />
          <Select
            className="!w-[140px]"
            allowClear
            placeholder="全部方向"
            value={direction}
            options={Object.entries(TRANSFER_DIRECTION).map(([value, label]) => ({ value, label }))}
            onChange={(value?: string) => setFilter('direction', value ?? '')}
          />
          <Select
            className="!w-[200px]"
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder="全部原公司"
            value={fromCompanyId}
            options={companyOptions}
            onChange={(value?: number) => setFilter('fromCompanyId', value ? String(value) : '')}
          />
          <Input
            className="!w-[260px]"
            allowClear
            placeholder="搜索流转原因 / 申请人（回车）"
            defaultValue={keyword}
            prefix={<SearchOutlined className="text-gray-300" />}
            onPressEnter={(e) => setKeyword(e.currentTarget.value.trim())}
          />
        </div>

        <div className="ams-table-wrap">
          <Table
            rowKey="id"
            size="small"
            loading={loading}
            columns={columns}
            dataSource={rows}
            scroll={{ x: 1500 }}
            locale={{
              emptyText: (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无权属流转单" />
              ),
            }}
            pagination={{
              current: page,
              pageSize,
              total,
              showSizeChanger: true,
              pageSizeOptions: ['10', '20', '50'],
              showTotal: (value) => `共 ${value} 条`,
              onChange: (nextPage, nextPageSize) => {
                // 两个值必须一起交给 useListQuery：分开调两次 setter 时后一次会用同一份
                // 旧 searchParams 覆盖前一次（lib/listQuery.ts 顶部已说明）
                if (nextPageSize !== pageSize) setPageSize(nextPageSize);
                else setPage(nextPage);
              },
            }}
          />
        </div>
      </div>

      <Drawer
        title={detail ? `权属流转单 #${detail.id}` : '权属流转单'}
        open={!!detail || detailLoading}
        onClose={() => setDetail(null)}
        width={Math.min(760, typeof window !== 'undefined' ? window.innerWidth - 32 : 760)}
        destroyOnHidden
      >
        {detail && (
          <div className="space-y-4">
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="流转方向">
                {TRANSFER_DIRECTION[detail.direction] ?? detail.direction}
              </Descriptions.Item>
              <Descriptions.Item label="权属类型">
                {TRANSFER_SCOPE[detail.transferScope] ?? detail.transferScope}
              </Descriptions.Item>
              <Descriptions.Item label="原公司">
                {companyLabel(detail.fromCompanyName, detail.fromCompanyId)}
              </Descriptions.Item>
              <Descriptions.Item label="新公司">
                {companyLabel(detail.toCompanyName, detail.toCompanyId)}
              </Descriptions.Item>
              <Descriptions.Item label="流转类型">
                {TRANSFER_MODE[detail.transferMode] ?? detail.transferMode}
              </Descriptions.Item>
              <Descriptions.Item label="变更申请人">{detail.applicantName}</Descriptions.Item>
              <Descriptions.Item label="审批截止时间">
                {formatTime(detail.approvalDeadline)}
              </Descriptions.Item>
              <Descriptions.Item label="金额(万元)">{detail.amountWan ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="流转原因">
                <span className="break-all">{detail.reason || '-'}</span>
              </Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={STATUS_COLOR[detail.status] ?? 'default'} className="m-0">
                  {OWNERSHIP_TRANSFER_STATUS[detail.status] ?? detail.status}
                </Tag>
                {detail.effectedAt && (
                  <span className="text-gray-500 ml-2">生效于 {formatTime(detail.effectedAt)}</span>
                )}
              </Descriptions.Item>
            </Descriptions>

            <div>
              <div className="text-sm font-medium text-gray-700 mb-1">
                资产（{detail.assets?.length ?? 0}）
              </div>
              <Table
                rowKey="assetId"
                size="small"
                pagination={false}
                dataSource={detail.assets ?? []}
                scroll={{ y: 240 }}
                columns={[
                  {
                    title: '资产',
                    key: 'asset',
                    render: (_, row) => (
                      <Tooltip title={row.assetNo ?? undefined} placement="topLeft">
                        <span>{assetOptionLabel(row)}</span>
                      </Tooltip>
                    ),
                  },
                ]}
                locale={{
                  emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="无" />,
                }}
              />
            </div>

            <div>
              <div className="text-sm font-medium text-gray-700 mb-1">
                附件（{detail.attachments?.length ?? 0}）
              </div>
              {(detail.attachments?.length ?? 0) === 0 ? (
                <div className="text-xs text-gray-400">无附件</div>
              ) : (
                <ul className="m-0 pl-4">
                  {/* 后端读路径回填的是 fileName（不是 name），缺省时退化成「附件<id>」 */}
                  {detail.attachments?.map((file) => (
                    <li key={file.fileId}>
                      <a href={file.url ?? '#'} target="_blank" rel="noreferrer">
                        {file.fileName ?? `附件${file.fileId}`}
                      </a>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        )}
      </Drawer>
    </div>
  );
}

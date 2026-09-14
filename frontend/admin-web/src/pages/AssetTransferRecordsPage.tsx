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
  SwapRightOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { useListQuery } from '@/lib/listQuery';
import { usePerm } from '@/lib/perm';
import { loadCompanies, type CompanyOption } from '@/lib/org';
import { ASSET_TRANSFER_RECORD_STATUS } from '@/lib/labels';
import { assetOptionLabel } from '@/lib/assetLabel';
import { companyLabel } from '@/lib/ownershipTransfer';
import {
  assetTransferRecordApi,
  departmentLabel,
  userLabel,
  type AssetTransferRecordView,
} from '@/lib/assetTransferRecord';

const CODE = 'deed.transferRecord';

const STATUS_COLOR: Record<string, string> = { draft: 'default', completed: 'green' };

const formatTime = (value?: string | null): string =>
  value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-';

/**
 * 资产调拨记录列表页。
 *
 * 筛选项只有三个（状态 / 所属公司 / 关键字）：本模块的维度就是「哪家公司、
 * 什么状态、因为什么」—— 前 / 新责任部门不做筛选，因为一张单可以跨部门，
 * 按部门筛会把「混部门」的单据整张漏掉或整张命中，两种结果都误导人。
 *
 * 单据没有单号列（与处置单 / 权属流转一致），界面用 `#id` 指代。
 */
export function AssetTransferRecordsPage() {
  const navigate = useNavigate();
  const can = usePerm();
  const canCreate = can(CODE, 'create');
  const canUpdate = can(CODE, 'update');
  const canDelete = can(CODE, 'delete');

  const list = useListQuery({
    filterKeys: ['status', 'companyId'],
    defaultPageSize: 10,
  });
  const { page, pageSize, keyword, filters, setPage, setPageSize, setKeyword, setFilter } = list;

  const status = filters.status || undefined;
  const companyId = filters.companyId ? Number(filters.companyId) : undefined;

  const [companies, setCompanies] = useState<CompanyOption[]>([]);
  const [rows, setRows] = useState<AssetTransferRecordView[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [detail, setDetail] = useState<AssetTransferRecordView | null>(null);
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
      const data = await assetTransferRecordApi.list({
        page,
        pageSize,
        status,
        companyId,
        keyword: keyword || undefined,
      });
      setRows(data.list);
      setTotal(data.total);
    } catch (e) {
      // 失败时清空而不是留着上一次的数据：留着会让人以为「筛选没生效」
      setRows([]);
      setTotal(0);
      message.error(e instanceof Error ? e.message : '加载资产调拨记录失败');
    } finally {
      setLoading(false);
    }
  }, [page, pageSize, status, companyId, keyword]);

  useEffect(() => {
    void load();
  }, [load]);

  const handleOpenDetail = useCallback(async (id: number) => {
    setDetailLoading(true);
    setDetail(null);
    try {
      const data = await assetTransferRecordApi.detail(id);
      setDetail(data);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载详情失败');
    } finally {
      setDetailLoading(false);
    }
  }, []);

  /**
   * 生效：会真的改写资产的「责任部门 / 责任人」，必须二次确认。
   *
   * 确认文案里写明「N 个资产的责任部门与责任人会被改写」而不是笼统的「确认生效？」——
   * 用户需要在这一刻知道影响面，而不是事后再去对账。
   */
  const handleEffect = useCallback(
    (row: AssetTransferRecordView) => {
      Modal.confirm({
        title: '确认生效？',
        content: (
          <div className="text-sm">
            <div>
              将把 <b>{row.assetCount}</b> 个资产的责任部门与责任人改写为「
              {departmentLabel(row.toDepartmentName, row.toDepartmentId)} /{' '}
              {userLabel(row.toUserName, row.toUserId)}」。
            </div>
            <div className="text-amber-600 mt-1">生效后不可撤销，要改回去只能再开一张单。</div>
          </div>
        ),
        okText: '确认生效',
        cancelText: '取消',
        onOk: async () => {
          setWorking(true);
          try {
            await assetTransferRecordApi.effect(row.id);
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
    (row: AssetTransferRecordView) => {
      Modal.confirm({
        title: '确认删除该草稿？',
        content: '删除后单据不再出现在列表与资产档案中；已完成的单据不能删除。',
        okText: '删除',
        okButtonProps: { danger: true },
        cancelText: '取消',
        onOk: async () => {
          setWorking(true);
          try {
            await assetTransferRecordApi.remove(row.id);
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

  const columns: ColumnsType<AssetTransferRecordView> = useMemo(
    () => [
      {
        title: '单据',
        dataIndex: 'id',
        key: 'id',
        width: 90,
        render: (_, row) => <span className="tabular-nums">#{row.id}</span>,
      },
      {
        title: '所属公司',
        dataIndex: 'companyName',
        key: 'companyName',
        width: 200,
        render: (_, row) => companyLabel(row.companyName, row.companyId),
      },
      {
        title: '前责任部门 → 新责任部门',
        key: 'departments',
        width: 280,
        render: (_, row) => (
          <span className="text-gray-800">
            {departmentLabel(row.fromDepartmentName, row.fromDepartmentId)}
            <SwapRightOutlined className="text-gray-400 mx-1" />
            <b>{departmentLabel(row.toDepartmentName, row.toDepartmentId)}</b>
          </span>
        ),
      },
      {
        title: '新责任人',
        dataIndex: 'toUserName',
        key: 'toUserName',
        width: 120,
        render: (_, row) => userLabel(row.toUserName, row.toUserId),
      },
      {
        title: '审批截止时间',
        dataIndex: 'approvalDeadline',
        key: 'approvalDeadline',
        width: 160,
        render: (_, row) => (
          <span className="tabular-nums text-gray-600">{formatTime(row.approvalDeadline)}</span>
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
            {ASSET_TRANSFER_RECORD_STATUS[row.status] ?? row.status}
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
                  onClick={() => navigate(`/asset-transfer-records/${row.id}/edit`)}
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
                aria-label={`删除资产调拨记录 #${row.id}`}
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
            <SwapRightOutlined className="text-[var(--ams-primary)]" />
            资产调拨记录
          </h1>
          <p className="text-sm text-gray-500 mt-1 mb-0">
            一次为多个资产变更责任部门与责任人（公司内部交接）；
            草稿可反复修改，生效后不可撤销
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
              onClick={() => navigate('/asset-transfer-records/new')}
            >
              新建调拨记录
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
              { value: 'draft', label: ASSET_TRANSFER_RECORD_STATUS.draft },
              { value: 'completed', label: ASSET_TRANSFER_RECORD_STATUS.completed },
            ]}
            onChange={(value?: string) => setFilter('status', value ?? '')}
          />
          <Select
            className="!w-[200px]"
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder="全部所属公司"
            value={companyId}
            options={companyOptions}
            onChange={(value?: number) => setFilter('companyId', value ? String(value) : '')}
          />
          <Input
            className="!w-[260px]"
            allowClear
            placeholder="搜索原因 / 备注（回车）"
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
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无资产调拨记录" />
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
        title={detail ? `资产调拨记录 #${detail.id}` : '资产调拨记录'}
        open={!!detail || detailLoading}
        onClose={() => setDetail(null)}
        width={Math.min(820, typeof window !== 'undefined' ? window.innerWidth - 32 : 820)}
        destroyOnHidden
      >
        {detail && (
          <div className="space-y-4">
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="所属公司">
                {companyLabel(detail.companyName, detail.companyId)}
              </Descriptions.Item>
              <Descriptions.Item label="前责任部门">
                {departmentLabel(detail.fromDepartmentName, detail.fromDepartmentId)}
              </Descriptions.Item>
              <Descriptions.Item label="新责任部门">
                {departmentLabel(detail.toDepartmentName, detail.toDepartmentId)}
              </Descriptions.Item>
              <Descriptions.Item label="新责任人">
                {userLabel(detail.toUserName, detail.toUserId)}
              </Descriptions.Item>
              <Descriptions.Item label="审批截止时间">
                {formatTime(detail.approvalDeadline)}
              </Descriptions.Item>
              <Descriptions.Item label="原因">
                <span className="break-all">{detail.reason || '-'}</span>
              </Descriptions.Item>
              <Descriptions.Item label="备注">
                <span className="break-all">{detail.remark || '-'}</span>
              </Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={STATUS_COLOR[detail.status] ?? 'default'} className="m-0">
                  {ASSET_TRANSFER_RECORD_STATUS[detail.status] ?? detail.status}
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
                scroll={{ y: 260 }}
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
                  {
                    title: '原责任部门 / 责任人',
                    key: 'from',
                    width: 220,
                    render: (_, row) =>
                      `${departmentLabel(row.fromDepartmentName, row.fromDepartmentId)} / ${userLabel(
                        row.fromUserName,
                        row.fromUserId,
                      )}`,
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

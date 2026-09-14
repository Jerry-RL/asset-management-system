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
  IdcardOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  SendOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { useListQuery } from '@/lib/listQuery';
import { usePerm } from '@/lib/perm';
import { loadCompanies, type CompanyOption } from '@/lib/org';
import { MORTGAGE_STATUS } from '@/lib/labels';
import { companyLabel } from '@/lib/ownershipTransfer';
import {
  mortgageRecordApi,
  mortgageTargetLabel,
  mortgageTargetTypeLabel,
  type MortgageRecordView,
} from '@/lib/mortgageRecord';

const CODE = 'deed.mortgage';

const STATUS_COLOR: Record<string, string> = {
  draft: 'default',
  active: 'red',
  released: 'green',
};

const TARGET_OPTIONS = [
  { value: 'project', label: '项目' },
  { value: 'zone', label: '分区' },
  { value: 'asset', label: '资产' },
];

const formatDate = (value?: string | null): string =>
  value ? dayjs(value).format('YYYY-MM-DD') : '-';

/**
 * 金额展示（万元）。
 *
 * 后端是 `BigDecimal`，JSON 里可能回来数字也可能回来字符串 —— 两种都要能显示。
 * 千分位不是装饰：抵押金额动辄六到八位数，不分组时「5000000」与「500000」很容易看错一位。
 */
const formatAmount = (value?: number | string | null): string => {
  if (value === null || value === undefined || value === '') return '-';
  const numeric = typeof value === 'number' ? value : Number(value);
  if (Number.isNaN(numeric)) return String(value);
  return numeric.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
};

const formatRate = (value?: number | string | null): string => {
  if (value === null || value === undefined || value === '') return '-';
  const numeric = typeof value === 'number' ? value : Number(value);
  return Number.isNaN(numeric) ? String(value) : `${numeric}%`;
};

/**
 * 抵押记录列表页（V56）。
 *
 * 这一页替换了原先由 `ResourcePage` 渲染的只读「抵押列表」（`GET /mortgages` 返回全表数组）。
 * 替换的理由不是「想要更好看」：那张列表每列都只显示 `assetId`，而 V56 之后标的三分之二
 * 是项目与分区 —— 继续用 `assetId` 列会让这些行的标的列一片空白。
 *
 * 筛选项三个（状态 / 项目类型 / 所属公司 + 关键字）：本模块的检索线索就是
 * 「哪家的、抵什么、什么状态」，以及「人 / 银行 / 合同号」这三个用户手里真实有的东西。
 */
export function MortgagesPage() {
  const navigate = useNavigate();
  const can = usePerm();
  const canCreate = can(CODE, 'create');
  const canUpdate = can(CODE, 'update');
  const canDelete = can(CODE, 'delete');

  const list = useListQuery({
    filterKeys: ['status', 'targetType', 'companyId'],
    defaultPageSize: 10,
  });
  const { page, pageSize, keyword, filters, setPage, setPageSize, setKeyword, setFilter } = list;

  const status = filters.status || undefined;
  const targetType = filters.targetType || undefined;
  const companyId = filters.companyId ? Number(filters.companyId) : undefined;

  const [companies, setCompanies] = useState<CompanyOption[]>([]);
  const [rows, setRows] = useState<MortgageRecordView[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [detail, setDetail] = useState<MortgageRecordView | null>(null);
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
      const data = await mortgageRecordApi.list({
        page,
        pageSize,
        status,
        targetType,
        companyId,
        keyword: keyword || undefined,
      });
      setRows(data.list);
      setTotal(data.total);
    } catch (e) {
      // 失败时清空而不是留着上一次的数据：留着会让人以为「筛选没生效」
      setRows([]);
      setTotal(0);
      message.error(e instanceof Error ? e.message : '加载抵押记录失败');
    } finally {
      setLoading(false);
    }
  }, [page, pageSize, status, targetType, companyId, keyword]);

  useEffect(() => {
    void load();
  }, [load]);

  const handleOpenDetail = useCallback(async (id: number) => {
    setDetailLoading(true);
    setDetail(null);
    try {
      const data = await mortgageRecordApi.detail(id);
      setDetail(data);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载详情失败');
    } finally {
      setDetailLoading(false);
    }
  }, []);

  /**
   * 生效：从这一刻起这条记录开始**拦截**标的的处置 / 流转 / 调拨。
   *
   * 确认文案必须说清「拦截范围」—— 项目 / 分区级抵押会连带影响它下面所有资产，
   * 只说「确认生效？」会让人以为影响面就是这一行。
   */
  const handleEffect = useCallback(
    (row: MortgageRecordView) => {
      const scope =
        row.targetType === 'asset'
          ? '该资产'
          : `该项目${row.targetType === 'zone' ? '分区' : ''}下的所有资产`;
      Modal.confirm({
        title: '确认生效？',
        content: (
          <div className="text-sm">
            <div>
              标的是「{mortgageTargetLabel(row.targetType, row)}」，生效后 {scope} 将处于
              <b>在押</b>状态，处置 / 流转 / 调拨都会被拦下。
            </div>
            <div className="text-amber-600 mt-1">
              生效后不可撤销；要解除抵押须走解押审批流程。
            </div>
          </div>
        ),
        okText: '确认生效',
        cancelText: '取消',
        onOk: async () => {
          setWorking(true);
          try {
            await mortgageRecordApi.effect(row.id);
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
    (row: MortgageRecordView) => {
      Modal.confirm({
        title: '确认删除该草稿？',
        content: '删除后单据不再出现在列表与资产档案中；已生效的记录不能删除（只能解押）。',
        okText: '删除',
        okButtonProps: { danger: true },
        cancelText: '取消',
        onOk: async () => {
          setWorking(true);
          try {
            await mortgageRecordApi.remove(row.id);
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

  const columns: ColumnsType<MortgageRecordView> = useMemo(
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
        width: 180,
        render: (_, row) => companyLabel(row.companyName, row.companyId),
      },
      {
        title: '项目类型',
        dataIndex: 'targetType',
        key: 'targetType',
        width: 90,
        render: (_, row) => (
          <Tag className="m-0">{mortgageTargetTypeLabel(row.targetType)}</Tag>
        ),
      },
      {
        title: '抵押标的',
        key: 'target',
        width: 300,
        render: (_, row) => (
          <Tooltip
            title={row.assetNo ?? undefined}
            placement="topLeft"
            // 项目 / 分区标的没有资产编号，空 Tooltip 会在悬停时闪一个空框
            open={row.assetNo ? undefined : false}
          >
            <span>{mortgageTargetLabel(row.targetType, row)}</span>
          </Tooltip>
        ),
      },
      {
        title: '抵押公司/人',
        dataIndex: 'mortgagee',
        key: 'mortgagee',
        width: 180,
        render: (_, row) => row.mortgagee || '-',
      },
      {
        title: '抵押金额',
        dataIndex: 'amount',
        key: 'amount',
        width: 140,
        align: 'right',
        render: (_, row) => (
          <span className="tabular-nums">{formatAmount(row.amount)}</span>
        ),
      },
      {
        title: '利率',
        dataIndex: 'interestRate',
        key: 'interestRate',
        width: 90,
        align: 'right',
        render: (_, row) => <span className="tabular-nums">{formatRate(row.interestRate)}</span>,
      },
      {
        title: '抵押期间',
        key: 'period',
        width: 200,
        render: (_, row) => (
          <span className="tabular-nums text-gray-600">
            {formatDate(row.startDate)} ~ {formatDate(row.endDate)}
          </span>
        ),
      },
      {
        title: '还款日',
        dataIndex: 'repaymentDate',
        key: 'repaymentDate',
        width: 120,
        render: (_, row) => (
          <span className="tabular-nums text-gray-600">{formatDate(row.repaymentDate)}</span>
        ),
      },
      {
        title: '状态',
        dataIndex: 'status',
        key: 'status',
        width: 90,
        render: (_, row) => (
          <Tag color={STATUS_COLOR[row.status] ?? 'default'} className="m-0">
            {MORTGAGE_STATUS[row.status] ?? row.status}
          </Tag>
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
                  onClick={() => navigate(`/mortgages/${row.id}/edit`)}
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
                aria-label={`删除抵押记录 #${row.id}`}
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
            <IdcardOutlined className="text-[var(--ams-primary)]" />
            抵押记录
          </h1>
          <p className="text-sm text-gray-500 mt-1 mb-0">
            对项目、项目分区或资产登记抵押；草稿可反复修改，生效后标的进入在押状态并被前置校验拦截
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
              onClick={() => navigate('/mortgages/new')}
            >
              新建抵押记录
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
              { value: 'draft', label: MORTGAGE_STATUS.draft },
              { value: 'active', label: MORTGAGE_STATUS.active },
              { value: 'released', label: MORTGAGE_STATUS.released },
            ]}
            onChange={(value?: string) => setFilter('status', value ?? '')}
          />
          <Select
            className="!w-[130px]"
            allowClear
            placeholder="全部项目类型"
            value={targetType}
            options={TARGET_OPTIONS}
            onChange={(value?: string) => setFilter('targetType', value ?? '')}
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
            placeholder="搜索抵押公司/人、银行、合同编号（回车）"
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
            scroll={{ x: 1700 }}
            locale={{
              emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无抵押记录" />,
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
        title={detail ? `抵押记录 #${detail.id}` : '抵押记录'}
        open={!!detail || detailLoading}
        onClose={() => setDetail(null)}
        width={Math.min(760, typeof window !== 'undefined' ? window.innerWidth - 32 : 760)}
        destroyOnHidden
      >
        {detail && (
          <div className="space-y-4">
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="所属公司">
                {companyLabel(detail.companyName, detail.companyId)}
              </Descriptions.Item>
              <Descriptions.Item label="项目类型">
                <Tag className="m-0">{mortgageTargetTypeLabel(detail.targetType)}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="抵押标的">
                {mortgageTargetLabel(detail.targetType, detail)}
              </Descriptions.Item>
              <Descriptions.Item label="抵押公司/人">{detail.mortgagee || '-'}</Descriptions.Item>
              <Descriptions.Item label="抵押金额">
                <span className="tabular-nums">{formatAmount(detail.amount)}</span>
              </Descriptions.Item>
              <Descriptions.Item label="利率">{formatRate(detail.interestRate)}</Descriptions.Item>
              <Descriptions.Item label="抵押银行">{detail.bank || '-'}</Descriptions.Item>
              <Descriptions.Item label="抵押起始时间">{formatDate(detail.startDate)}</Descriptions.Item>
              <Descriptions.Item label="抵押期限（月）">
                {detail.termMonths ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label="抵押到期日">
                <span className="tabular-nums">{formatDate(detail.endDate)}</span>
                <span className="text-gray-400 ml-2 text-xs">由起始时间 + 期限推导</span>
              </Descriptions.Item>
              <Descriptions.Item label="还款日">{formatDate(detail.repaymentDate)}</Descriptions.Item>
              <Descriptions.Item label="抵押合同编号">{detail.contractNo || '-'}</Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={STATUS_COLOR[detail.status] ?? 'default'} className="m-0">
                  {MORTGAGE_STATUS[detail.status] ?? detail.status}
                </Tag>
                {detail.releasedAt && (
                  <span className="text-gray-500 ml-2">
                    解押于 {dayjs(detail.releasedAt).format('YYYY-MM-DD HH:mm')}
                  </span>
                )}
                {detail.status === 'active' && detail.releaseStatus === 'releasing' && (
                  <span className="text-gray-500 ml-2">解押审批中</span>
                )}
                {/* 解押被驳回时，驳回原因就写在 releaseRemark 里 —— 不显示的话用户只看到
                    「在押」，不知道为什么申请没通过 */}
                {detail.releaseRemark && (
                  <div className="text-gray-500 text-xs mt-1 break-all">
                    解押说明：{detail.releaseRemark}
                  </div>
                )}
              </Descriptions.Item>
            </Descriptions>

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

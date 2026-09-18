import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Button,
  Descriptions,
  Drawer,
  Empty,
  Input,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ExportOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { useListQuery } from '@/lib/listQuery';
import { loadCompanies, type CompanyOption } from '@/lib/org';
import { useDictOptions } from '@/lib/dict';
import { assetOptionLabel } from '@/lib/assetLabel';
import { companyLabel } from '@/lib/ownershipTransfer';
import {
  assetDisposalRecordApi,
  disposalAmountLabel,
  disposalTargetLabel,
  DISPOSAL_TARGET_TYPE,
  type AssetDisposalRecordView,
} from '@/lib/disposalRecord';

const formatDate = (value?: string | null): string => (value ? value.slice(0, 10) : '-');

const formatTime = (value?: string | null): string =>
  value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-';

/**
 * 资产处置记录列表页（V57）—— **只读台账**：一行 = 一个被处置的资产。
 *
 * <p>写入不在这里：资产级处置在「资产处置」完成时级联，项目 / 分区级处置在后续记录的
 * 处置段登记时级联。因此本页没有新建 / 编辑 / 删除按钮 —— 处置记录是已经发生的事实的快照，
 * 可编辑意味着可以伪造处置历史。
 *
 * <p>筛选项四个（原产权公司 / 处置对象 / 处置方式 / 关键字）。**原产权公司**是核心维度：
 * 资产上的 {@code property_company_id} 已被清空，只有这里的快照还能回答「从哪家公司处置出去」。
 */
export function DisposalRecordsPage() {
  const list = useListQuery({
    filterKeys: ['fromCompanyId', 'targetType', 'disposalType'],
    defaultPageSize: 10,
  });
  const { page, pageSize, keyword, filters, setPage, setPageSize, setKeyword, setFilter } = list;

  const fromCompanyId = filters.fromCompanyId ? Number(filters.fromCompanyId) : undefined;
  const targetType = filters.targetType || undefined;
  const disposalType = filters.disposalType || undefined;

  const disposalTypeOptions = useDictOptions('disposal_type');

  const [companies, setCompanies] = useState<CompanyOption[]>([]);
  const [rows, setRows] = useState<AssetDisposalRecordView[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [detail, setDetail] = useState<AssetDisposalRecordView | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  useEffect(() => {
    void loadCompanies().then(setCompanies);
  }, []);

  const companyOptions = useMemo(
    () => companies.map((company) => ({ value: company.id, label: company.name })),
    [companies],
  );

  /** 处置方式中文名：以字典为准，字典未加载时回落到后端映射（与后端写入口径一致）。 */
  const disposalTypeLabel = useCallback(
    (value?: string | null): string => {
      if (!value) return '-';
      const hit = disposalTypeOptions.options.find((option) => option.value === value);
      return hit ? String(hit.label) : value;
    },
    [disposalTypeOptions.options],
  );

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await assetDisposalRecordApi.list({
        page,
        pageSize,
        fromCompanyId,
        targetType,
        disposalType,
        keyword: keyword || undefined,
      });
      setRows(data.list);
      setTotal(data.total);
    } catch (e) {
      // 失败时清空而不是留着上一次的数据：留着会让人以为「筛选没生效」
      setRows([]);
      setTotal(0);
      message.error(e instanceof Error ? e.message : '加载资产处置记录失败');
    } finally {
      setLoading(false);
    }
  }, [page, pageSize, fromCompanyId, targetType, disposalType, keyword]);

  useEffect(() => {
    void load();
  }, [load]);

  const handleOpenDetail = useCallback(async (id: number) => {
    setDetailLoading(true);
    setDetail(null);
    try {
      setDetail(await assetDisposalRecordApi.detail(id));
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载详情失败');
    } finally {
      setDetailLoading(false);
    }
  }, []);

  const columns: ColumnsType<AssetDisposalRecordView> = useMemo(
    () => [
      {
        title: '资产',
        key: 'asset',
        width: 260,
        render: (_, row) => (
          <Tooltip title={row.assetNo ?? undefined} placement="topLeft">
            <span>{assetOptionLabel({ ...row, name: row.assetName })}</span>
          </Tooltip>
        ),
      },
      {
        title: '处置对象',
        dataIndex: 'targetType',
        key: 'targetType',
        width: 100,
        render: (_, row) => (
          <Tag className="m-0">{DISPOSAL_TARGET_TYPE[row.targetType] ?? row.targetType}</Tag>
        ),
      },
      {
        title: '原产权公司',
        dataIndex: 'fromPropertyCompanyName',
        key: 'fromPropertyCompanyName',
        width: 200,
        render: (_, row) =>
          companyLabel(row.fromPropertyCompanyName, row.fromPropertyCompanyId),
      },
      {
        title: '处置方式',
        dataIndex: 'disposalType',
        key: 'disposalType',
        width: 100,
        render: (_, row) => disposalTypeLabel(row.disposalType),
      },
      {
        title: '处置金额',
        key: 'disposalAmount',
        width: 140,
        align: 'right',
        render: (_, row) => (
          <span className="tabular-nums">
            {disposalAmountLabel(row.disposalAmount, row.amountUnit)}
          </span>
        ),
      },
      {
        title: '处置日期',
        dataIndex: 'disposalDate',
        key: 'disposalDate',
        width: 120,
        render: (_, row) => (
          <span className="tabular-nums text-gray-600">{formatDate(row.disposalDate)}</span>
        ),
      },
      {
        title: '处置人',
        dataIndex: 'disposalUserName',
        key: 'disposalUserName',
        width: 110,
        render: (_, row) => row.disposalUserName ?? '-',
      },
      {
        title: '处置时间',
        dataIndex: 'disposedAt',
        key: 'disposedAt',
        width: 150,
        render: (_, row) => (
          <span className="tabular-nums text-gray-600">{formatTime(row.disposedAt)}</span>
        ),
      },
      {
        title: '操作',
        key: 'actions',
        width: 80,
        fixed: 'right',
        render: (_, row) => (
          <Button
            type="link"
            size="small"
            className="p-0"
            onClick={() => void handleOpenDetail(row.id)}
          >
            详情
          </Button>
        ),
      },
    ],
    [disposalTypeLabel, handleOpenDetail],
  );

  return (
    <div className="space-y-4 min-w-0 max-w-full overflow-hidden">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-lg font-semibold text-gray-900 flex items-center gap-2 m-0">
            <ExportOutlined className="text-[var(--ams-primary)]" />
            资产处置记录
          </h1>
          <p className="text-sm text-gray-500 mt-1 mb-0">
            所有已处置的资产台账；处置完成后资产已脱离原产权公司，此处保留原产权公司快照以供追溯
          </p>
        </div>
        <Space wrap size={[8, 8]}>
          <Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>
            刷新
          </Button>
        </Space>
      </div>

      <div className="bg-white rounded-xl border border-[var(--ams-border)] p-3 sm:p-4">
        <div className="flex flex-wrap items-center gap-2 mb-3">
          <Select
            className="!w-[220px]"
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder="全部原产权公司"
            value={fromCompanyId}
            options={companyOptions}
            onChange={(value?: number) => setFilter('fromCompanyId', value ? String(value) : '')}
          />
          <Select
            className="!w-[130px]"
            allowClear
            placeholder="全部处置对象"
            value={targetType}
            options={Object.entries(DISPOSAL_TARGET_TYPE).map(([value, label]) => ({
              value,
              label,
            }))}
            onChange={(value?: string) => setFilter('targetType', value ?? '')}
          />
          <Select
            className="!w-[140px]"
            allowClear
            placeholder="全部处置方式"
            value={disposalType}
            options={disposalTypeOptions.options}
            onChange={(value?: string) => setFilter('disposalType', value ?? '')}
          />
          <Input
            className="!w-[260px]"
            allowClear
            placeholder="搜索资产编号 / 名称（回车）"
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
            scroll={{ x: 1400 }}
            locale={{
              emptyText: (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无资产处置记录" />
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
        title={detail ? `资产处置记录 #${detail.id}` : '资产处置记录'}
        open={!!detail || detailLoading}
        onClose={() => setDetail(null)}
        width={Math.min(720, typeof window !== 'undefined' ? window.innerWidth - 32 : 720)}
        destroyOnHidden
      >
        {detail && (
          <div className="space-y-4">
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="资产">
                {assetOptionLabel({ ...detail, name: detail.assetName })}
              </Descriptions.Item>
              <Descriptions.Item label="资产编号">{detail.assetNo ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="处置对象">
                {disposalTargetLabel(detail.targetType, detail.targetId)}
              </Descriptions.Item>
              <Descriptions.Item label="原产权公司">
                {companyLabel(detail.fromPropertyCompanyName, detail.fromPropertyCompanyId)}
              </Descriptions.Item>
              <Descriptions.Item label="原经营公司">
                {companyLabel(detail.fromOperatingCompanyName, detail.fromOperatingCompanyId)}
              </Descriptions.Item>
              <Descriptions.Item label="处置方式">
                {disposalTypeLabel(detail.disposalType)}
              </Descriptions.Item>
              <Descriptions.Item label="处置金额">
                {disposalAmountLabel(detail.disposalAmount, detail.amountUnit)}
              </Descriptions.Item>
              <Descriptions.Item label="处置日期">{formatDate(detail.disposalDate)}</Descriptions.Item>
              <Descriptions.Item label="处置人">{detail.disposalUserName ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="备注">
                <span className="break-all">{detail.remark || '-'}</span>
              </Descriptions.Item>
              <Descriptions.Item label="处置时间">{formatTime(detail.disposedAt)}</Descriptions.Item>
              <Descriptions.Item label="来源单据">
                {detail.sourceOrderId != null
                  ? `资产处置单 #${detail.sourceOrderId}`
                  : detail.sourceRecordId != null
                    ? `处置台账 #${detail.sourceRecordId}`
                    : '-'}
              </Descriptions.Item>
            </Descriptions>
          </div>
        )}
      </Drawer>
    </div>
  );
}

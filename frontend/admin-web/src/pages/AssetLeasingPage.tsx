import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Button,
  Card,
  Descriptions,
  Drawer,
  Empty,
  Input,
  Modal,
  Space,
  Table,
  Tabs,
  Tag,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  EyeOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { api, type PageResult } from '@/lib/api';
import { useListQuery } from '@/lib/listQuery';
import { LEASE_CONTROL_STATUS, LISTING_STATUS, RENT_TYPE, enumLabel } from '@/lib/labels';
import { CoverImage } from '@/components/CoverImage';
import { TableActions, actionsColumnWidth } from '@/components/TableActions';
import {
  PublishListingModal,
  type ListingPublishFormValues,
} from '@/components/PublishListingModal';

/** 资产行（`/assets` 返回，含列表回显的非表字段）。 */
interface AssetRow {
  id: number;
  assetNo?: string;
  name?: string;
  imageUrl?: string;
  address?: string;
  projectName?: string;
  zoneName?: string;
  floorNo?: number;
  area?: number;
  leaseArea?: number;
  leaseControlStatus?: string;
  responsibleUserName?: string;
}

/** 招租发布记录（`/lease-listings`）。 */
interface ListingRow {
  id: number;
  assetId: number;
  status: string;
  rentType?: string;
  annualRent?: number;
  rentNegotiable?: boolean;
  recommended?: boolean;
  sortNo?: number;
  intro?: string;
  remark?: string;
  rejectReason?: string;
  coverImageUrl?: string;
  coverImageFileId?: number;
  detailImages?: { url: string; fileId?: number }[];
  createdAt?: string;
}

/** 招租详情（`/lease-listings/{id}`）：招租字段 + 资产详情 + 发起人。 */
interface ListingDetail {
  listing: ListingRow;
  asset: AssetRow;
  createdByName?: string;
  createdByPhone?: string;
}

/**
 * Tab 是**资产租控状态**：需求指定「全部 / 租赁中 / 招租中 / 自用中」，
 * 这里补齐与之同源的「空置 / 部分出租」，让「全部」之外的状态都能直达。
 */
const LEASE_TABS = [
  { key: '', label: '全部' },
  { key: 'leasing', label: '招租中' },
  { key: 'leased', label: '租赁中' },
  { key: 'self_use', label: '自用中' },
  { key: 'vacant', label: '空置' },
  { key: 'partial_leased', label: '部分出租' },
];

const formatTime = (value?: string): string => (value ? value.slice(0, 19).replace('T', ' ') : '-');

/**
 * 资产租赁管理（FR-OPS-002，V59）。
 *
 * <p>Tab 是**资产租控状态**，列表是**资产** —— 这一页回答「哪些资产正处于什么运营状态」，
 * 与「招租管理」回答的「有哪些招租发布单据」是两件事，故不复用同一个列表。
 *
 * <p>「发布招租」是资产维度的动作：从行内发起时资产由行数据注入（需求：「在列表中操作
 * 无需选择」），从工具栏发起时才需要选资产。提交后进入招租发布审批，在「招租中」Tab
 * 打开「招租记录」即可看到待审批 / 已驳回条目与驳回原因。
 *
 * <p>资产详情与发起人信息只在**查看详情**时露出（需求约定），列表与抽屉都不展示。
 */
export function AssetLeasingPage() {
  const navigate = useNavigate();
  const { page, pageSize, keyword, filters, setPage, setPageSize, setKeyword, setFilter } =
    useListQuery({ filterKeys: ['leaseControlStatus'], defaultPageSize: 10 });
  const leaseControlStatus = filters.leaseControlStatus || '';

  const [rows, setRows] = useState<AssetRow[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);

  const [publishOpen, setPublishOpen] = useState(false);
  const [publishAsset, setPublishAsset] = useState<AssetRow | null>(null);
  const [resubmit, setResubmit] = useState<{
    id: number;
    values: ListingPublishFormValues;
  } | null>(null);

  const [listingAsset, setListingAsset] = useState<AssetRow | null>(null);
  const [listings, setListings] = useState<ListingRow[]>([]);
  const [listingsLoading, setListingsLoading] = useState(false);

  const [detail, setDetail] = useState<ListingDetail | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
      if (keyword) params.set('keyword', keyword);
      if (leaseControlStatus) params.set('leaseControlStatus', leaseControlStatus);
      const data = await api.get<PageResult<AssetRow>>(`/assets?${params.toString()}`);
      setRows(data?.list ?? []);
      setTotal(data?.total ?? 0);
    } catch (e) {
      // 失败时清空而不是留着上一次的数据：留着会让人以为「筛选没生效」
      setRows([]);
      setTotal(0);
      message.error(e instanceof Error ? e.message : '加载资产失败');
    } finally {
      setLoading(false);
    }
  }, [page, pageSize, keyword, leaseControlStatus]);

  useEffect(() => {
    void load();
  }, [load]);

  const loadListings = useCallback(async (assetId: number) => {
    setListingsLoading(true);
    try {
      const data = await api.get<ListingRow[]>(`/lease-listings?assetId=${assetId}`);
      setListings(data ?? []);
    } catch (e) {
      setListings([]);
      message.error(e instanceof Error ? e.message : '加载招租记录失败');
    } finally {
      setListingsLoading(false);
    }
  }, []);

  const handleOpenListings = useCallback(
    (asset: AssetRow) => {
      setListingAsset(asset);
      setListings([]);
      void loadListings(asset.id);
    },
    [loadListings],
  );

  const handleOpenDetail = useCallback(async (listingId: number) => {
    try {
      setDetail(await api.get<ListingDetail>(`/lease-listings/${listingId}`));
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载招租详情失败');
    }
  }, []);

  const handleClose = useCallback(
    async (listing: ListingRow) => {
      try {
        await api.post(`/lease-listings/${listing.id}/close`, {});
        message.success('已关闭招租');
        if (listingAsset) await loadListings(listingAsset.id);
        await load();
      } catch (e) {
        message.error(e instanceof Error ? e.message : '关闭失败');
      }
    },
    [listingAsset, loadListings, load],
  );

  /**
   * 「重新提交」需要把原表单值回填：列表接口给的是摊平列
   * （`coverImageUrl` / `coverImageFileId`），这里映射回表单的 `{url,fileId}` 形态。
   */
  const resubmitValues = useCallback(
    (listing: ListingRow): ListingPublishFormValues => ({
      assetId: listing.assetId,
      rentType: listing.rentType,
      annualRent: listing.annualRent,
      rentNegotiable: listing.rentNegotiable,
      recommended: listing.recommended,
      sortNo: listing.sortNo,
      intro: listing.intro,
      remark: listing.remark,
      coverImage:
        listing.coverImageUrl || listing.coverImageFileId
          ? { url: listing.coverImageUrl ?? '', fileId: listing.coverImageFileId }
          : null,
      detailImages: listing.detailImages ?? [],
    }),
    [],
  );

  const assetLabel = useCallback(
    (asset: AssetRow) => [asset.assetNo, asset.name].filter(Boolean).join(' · '),
    [],
  );

  const handleResubmit = useCallback(
    (listing: ListingRow) => {
      setResubmit({ id: listing.id, values: resubmitValues(listing) });
      setPublishAsset({ id: listing.assetId });
      setPublishOpen(true);
    },
    [resubmitValues],
  );

  const columns = useMemo<ColumnsType<AssetRow>>(
    () => [
      {
        title: '封面',
        dataIndex: 'imageUrl',
        width: 90,
        render: (value: string) => <CoverImage src={value} alt="资产封面" className="h-12 w-16" />,
      },
      { title: '资产编号', dataIndex: 'assetNo', width: 150, ellipsis: true },
      { title: '名称', dataIndex: 'name', ellipsis: true },
      { title: '项目', dataIndex: 'projectName', width: 140, ellipsis: true },
      { title: '分区', dataIndex: 'zoneName', width: 120, ellipsis: true },
      { title: '楼层', dataIndex: 'floorNo', width: 70 },
      { title: '资产面积(㎡)', dataIndex: 'area', width: 110 },
      { title: '租赁面积(㎡)', dataIndex: 'leaseArea', width: 110 },
      {
        title: '租控状态',
        dataIndex: 'leaseControlStatus',
        width: 110,
        render: (value: string) => (
          <Tag color={value === 'leasing' ? 'blue' : value === 'leased' ? 'green' : undefined}>
            {enumLabel(LEASE_CONTROL_STATUS, value)}
          </Tag>
        ),
      },
      { title: '责任人', dataIndex: 'responsibleUserName', width: 100, ellipsis: true },
      {
        title: '操作',
        key: 'actions',
        fixed: 'right',
        width: actionsColumnWidth(['发布招租', '招租记录', '详情']),
        render: (_: unknown, row: AssetRow) => (
          <TableActions
            actions={[
              {
                key: 'publish',
                label: '发布招租',
                icon: <PlusOutlined />,
                // 仅空置资产可发起（与后端 assertVacant 同口径）：把在租 / 自用资产也列进去，
                // 用户会填完整个表单才被驳回
                disabled: row.leaseControlStatus !== 'vacant',
                onClick: () => {
                  setResubmit(null);
                  setPublishAsset(row);
                  setPublishOpen(true);
                },
              },
              {
                key: 'listings',
                label: '招租记录',
                icon: <UnorderedListOutlined />,
                onClick: () => handleOpenListings(row),
              },
              {
                key: 'detail',
                label: '详情',
                icon: <EyeOutlined />,
                onClick: () => navigate(`/assets/${row.id}/dossier`),
              },
            ]}
            max={3}
          />
        ),
      },
    ],
    [handleOpenListings, navigate],
  );

  const listingColumns = useMemo<ColumnsType<ListingRow>>(
    () => [
      { title: 'ID', dataIndex: 'id', width: 70 },
      {
        title: '状态',
        dataIndex: 'status',
        width: 100,
        render: (value: string) => (
          <Tag color={value === 'active' ? 'green' : value === 'rejected' ? 'red' : 'processing'}>
            {enumLabel(LISTING_STATUS, value)}
          </Tag>
        ),
      },
      {
        title: '租金类型',
        dataIndex: 'rentType',
        width: 100,
        render: (value: string) => enumLabel(RENT_TYPE, value),
      },
      {
        title: '年租金(元)',
        dataIndex: 'annualRent',
        width: 130,
        render: (value: number) => (value == null ? '-' : value.toLocaleString()),
      },
      {
        title: '推荐',
        dataIndex: 'recommended',
        width: 80,
        render: (value: boolean) => (value ? <Tag color="gold">推荐</Tag> : '-'),
      },
      { title: '排序', dataIndex: 'sortNo', width: 70 },
      {
        title: '驳回原因',
        dataIndex: 'rejectReason',
        ellipsis: true,
        render: (value: string) => value || '-',
      },
      {
        title: '发起时间',
        dataIndex: 'createdAt',
        width: 170,
        render: (value: string) => formatTime(value),
      },
      {
        title: '操作',
        key: 'actions',
        fixed: 'right',
        width: actionsColumnWidth(['详情', '重新提交', '关闭']),
        render: (_: unknown, row: ListingRow) => (
          <TableActions
            actions={[
              {
                key: 'detail',
                label: '详情',
                icon: <EyeOutlined />,
                onClick: () => void handleOpenDetail(row.id),
              },
              {
                key: 'resubmit',
                label: '重新提交',
                // 只有驳回态能重报（与后端 resubmit 的状态守卫同口径）
                disabled: row.status !== 'rejected',
                onClick: () => handleResubmit(row),
              },
              {
                key: 'close',
                label: '关闭',
                danger: true,
                disabled: row.status === 'closed',
                onClick: () => void handleClose(row),
              },
            ]}
            max={3}
          />
        ),
      },
    ],
    [handleClose, handleOpenDetail, handleResubmit],
  );

  const closePublishModal = useCallback(() => {
    setPublishOpen(false);
    setResubmit(null);
    setPublishAsset(null);
  }, []);

  const handlePublishSuccess = useCallback(() => {
    closePublishModal();
    void load();
    // 抽屉可能正开着同一条资产的招租记录（行内发布后立即查看），一并刷新
    if (listingAsset) void loadListings(listingAsset.id);
  }, [closePublishModal, listingAsset, load, loadListings]);

  return (
    <div className="p-4 space-y-4">
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <div>
          <h1 className="text-xl font-semibold m-0">资产租赁管理</h1>
          <p className="text-gray-500 text-sm m-0 mt-1">
            按租控状态查看资产；发布招租需经审批，通过后小程序端可见
          </p>
        </div>
        <Space>
          <Input
            allowClear
            value={keyword}
            prefix={<SearchOutlined />}
            placeholder="资产编号 / 名称"
            style={{ width: 220 }}
            aria-label="关键字"
            onChange={(e) => setKeyword(e.target.value)}
          />
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => {
              setResubmit(null);
              setPublishAsset(null);
              setPublishOpen(true);
            }}
          >
            发布招租
          </Button>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
        </Space>
      </div>

      <Tabs
        activeKey={leaseControlStatus}
        items={LEASE_TABS}
        onChange={(key) => setFilter('leaseControlStatus', key)}
      />

      <Card size="small">
        <Table
          rowKey="id"
          loading={loading}
          dataSource={rows}
          columns={columns}
          scroll={{ x: 1500 }}
          locale={{ emptyText: <Empty description="该状态下暂无资产" /> }}
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

      <PublishListingModal
        open={publishOpen}
        assetId={publishAsset?.id}
        assetLabel={publishAsset ? assetLabel(publishAsset) : undefined}
        resubmit={resubmit}
        onClose={closePublishModal}
        onSuccess={handlePublishSuccess}
      />

      <Drawer
        open={listingAsset != null}
        title={`招租记录 · ${listingAsset ? assetLabel(listingAsset) : ''}`}
        width={900}
        onClose={() => setListingAsset(null)}
        destroyOnHidden
      >
        <Table
          rowKey="id"
          loading={listingsLoading}
          dataSource={listings}
          columns={listingColumns}
          scroll={{ x: 1100 }}
          locale={{ emptyText: <Empty description="该资产暂无招租发布记录" /> }}
          pagination={false}
        />
      </Drawer>

      <Modal
        open={detail != null}
        title="招租详情"
        footer={null}
        width={760}
        onCancel={() => setDetail(null)}
        destroyOnHidden
      >
        {detail && (
          <div className="space-y-4">
            <Descriptions title="招租信息" size="small" column={2} bordered>
              <Descriptions.Item label="状态">
                {enumLabel(LISTING_STATUS, detail.listing.status)}
              </Descriptions.Item>
              <Descriptions.Item label="租金类型">
                {enumLabel(RENT_TYPE, detail.listing.rentType)}
              </Descriptions.Item>
              <Descriptions.Item label="年租金(元)">
                {detail.listing.annualRent?.toLocaleString() ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label="可议价">
                {detail.listing.rentNegotiable ? '是' : '否'}
              </Descriptions.Item>
              <Descriptions.Item label="推荐">
                {detail.listing.recommended ? '是' : '否'}
              </Descriptions.Item>
              <Descriptions.Item label="排序">{detail.listing.sortNo ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="介绍" span={2}>
                {detail.listing.intro || '-'}
              </Descriptions.Item>
              <Descriptions.Item label="招租说明" span={2}>
                {detail.listing.remark || '-'}
              </Descriptions.Item>
              {detail.listing.rejectReason && (
                <Descriptions.Item label="驳回原因" span={2}>
                  <span className="text-red-600">{detail.listing.rejectReason}</span>
                </Descriptions.Item>
              )}
              <Descriptions.Item label="封面" span={2}>
                <CoverImage
                  src={detail.listing.coverImageUrl}
                  alt="招租封面"
                  className="h-20 w-28"
                />
              </Descriptions.Item>
              <Descriptions.Item label="详情列表图" span={2}>
                {detail.listing.detailImages?.length ? (
                  <div className="flex flex-wrap gap-2">
                    {detail.listing.detailImages.map((img) => (
                      <CoverImage
                        key={`${img.fileId ?? ''}-${img.url}`}
                        src={img.url}
                        alt="详情列表图"
                        className="h-20 w-28"
                      />
                    ))}
                  </div>
                ) : (
                  '-'
                )}
              </Descriptions.Item>
            </Descriptions>

            <Descriptions title="资产详情" size="small" column={2} bordered>
              <Descriptions.Item label="资产编号">{detail.asset.assetNo ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="名称">{detail.asset.name ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="项目">{detail.asset.projectName ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="分区">{detail.asset.zoneName ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="楼层">{detail.asset.floorNo ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="租控状态">
                {enumLabel(LEASE_CONTROL_STATUS, detail.asset.leaseControlStatus)}
              </Descriptions.Item>
              <Descriptions.Item label="资产面积(㎡)">{detail.asset.area ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="租赁面积(㎡)">
                {detail.asset.leaseArea ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label="坐落" span={2}>
                {detail.asset.address || '-'}
              </Descriptions.Item>
            </Descriptions>

            <Descriptions title="发起人信息" size="small" column={2} bordered>
              <Descriptions.Item label="发起人">{detail.createdByName ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="联系电话">{detail.createdByPhone ?? '-'}</Descriptions.Item>
            </Descriptions>
          </div>
        )}
      </Modal>
    </div>
  );
}

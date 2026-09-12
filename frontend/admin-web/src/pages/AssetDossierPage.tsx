import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  Button,
  Card,
  Descriptions,
  Empty,
  Modal,
  Space,
  Table,
  Tabs,
  Tag,
  Timeline,
  message,
} from 'antd';
import { ArrowLeftOutlined, EyeOutlined, ReloadOutlined } from '@ant-design/icons';
import { api } from '@/lib/api';
import { AssetQrLabel } from '@/components/AssetQrLabel';
import { AssetQuickActions } from '@/components/AssetQuickActions';
import { TableActions } from '@/components/TableActions';
import { useBackNavigate } from '@/lib/navigation';
import {
  ASSET_NATURE,
  ASSET_OWNERSHIP,
  ASSET_SOURCE,
  ASSET_TYPE,
  ASSET_USAGE,
  ASSET_HOUSE_TYPE,
  BILL_STATUS,
  BUILDING_PLAN,
  BUILDING_STRUCTURE,
  CONTRACT_STATUS,
  DISPOSAL_STATUS,
  LEASE_CONTROL_STATUS,
  METER_TYPE,
  MORTGAGE_STATUS,
  PARTIAL_LEASE_STATUS,
  PAYMENT_CYCLE,
  RENT_TYPE,
  REPAIR_STATUS,
  TRANSFER_STATUS,
  VACATE_STATUS,
  enumLabel,
} from '@/lib/labels';

type Row = Record<string, unknown>;

interface ContractDetail extends Row {
  id: number;
  contractNo?: string;
  status?: string;
  tenantId?: number;
  startDate?: string;
  endDate?: string;
  rentType?: string;
  rentAmount?: number;
  depositAmount?: number;
  leaseArea?: number;
  paymentCycle?: string;
  paymentStatus?: string;
  esignStatus?: string;
  remark?: string;
  version?: number;
  leaseMode?: string;
  freeRentDays?: number;
  prepayAmount?: number;
  docFileId?: number;
  docHtml?: string;
}

interface AssetRow {
  id: number;
  assetNo: string;
  name: string;
  assetType?: string;
  area?: number;
  leaseArea?: number;
  floorNo?: number;
  assetCompanyId?: number;
  assetCompanyName?: string;
  propertyCompanyId?: number;
  propertyCompanyName?: string;
  zoneName?: string;
  partialLeaseStatus?: string;
  assetNature?: string;
  buildingPlan?: string;
  registeredAt?: string;
  responsibleDepartmentId?: number;
  responsibleDepartmentName?: string;
  responsibleUserId?: number;
  responsibleUserName?: string;
  imageUrl?: string;
  leaseControlStatus?: string;
  structureStatus?: string;
  sourceType?: string;
  ownershipType?: string;
  usageType?: string;
  houseType?: string;
  province?: string;
  city?: string;
  district?: string;
  address?: string;
  structureType?: string;
  originalValue?: number;
  baseRentAssessed?: number;
  baseRentFloor?: number;
  marketRefRent?: number;
  waterMeterNo?: string;
  electricMeterNo?: string;
  vacantReason?: string;
  vacantSince?: string;
  projectId?: number;
  operatingCompanyId?: number;
  parentAssetId?: number;
  oldAssetNo?: string;
  qrCodeUrl?: string;
}

interface StatusSummary {
  leaseControlStatus?: string;
  structureStatus?: string;
  mortgaged?: boolean;
  activeContractCount?: number;
  unpaidBillCount?: number;
  arrearsAmount?: number;
  vacantSince?: string;
  vacantReason?: string;
  leaseControlChangeCount?: number;
  timelineCount?: number;
}

interface TimelineItem {
  category: string;
  title: string;
  status?: string;
  remark?: string;
  bizId?: number;
  occurredAt?: string;
}

interface AssetDossier {
  asset: AssetRow;
  statusSummary: StatusSummary;
  leaseControlLogs: Record<string, unknown>[];
  certificates: Record<string, unknown>[];
  mortgages: Record<string, unknown>[];
  transfers: Record<string, unknown>[];
  structureLogs: Record<string, unknown>[];
  contracts: Record<string, unknown>[];
  vacateOrders: Record<string, unknown>[];
  bills: Record<string, unknown>[];
  dunningRecords: Record<string, unknown>[];
  repairs: Record<string, unknown>[];
  inspections: Record<string, unknown>[];
  disposals: Record<string, unknown>[];
  occupations: Record<string, unknown>[];
  evaluations: Record<string, unknown>[];
  leaseListings: Record<string, unknown>[];
  meters: Record<string, unknown>[];
  timeline: TimelineItem[];
}

const STRUCTURE_STATUS: Record<string, string> = {
  active: '有效',
  frozen: '冻结',
  merged_out: '已合并退出',
};

const TIMELINE_COLOR: Record<string, string> = {
  lease_control: 'blue',
  contract: 'green',
  vacate: 'orange',
  bill: 'cyan',
  dunning: 'red',
  repair: 'purple',
  inspection: 'geekblue',
  transfer: 'gold',
  disposal: 'magenta',
  occupation: 'lime',
  evaluation: 'volcano',
  listing: 'blue',
  structure: 'default',
  mortgage: 'red',
};

const CATEGORY_LABEL: Record<string, string> = {
  lease_control: '租控',
  contract: '合同',
  vacate: '退租',
  bill: '账单',
  dunning: '催缴',
  repair: '维修',
  inspection: '巡查',
  transfer: '调拨',
  disposal: '处置',
  occupation: '占用',
  evaluation: '评估',
  listing: '招租',
  structure: '拆合',
  mortgage: '抵押',
};

const statusTag = (value?: string, map?: Record<string, string>) => {
  if (!value) return '-';
  return <Tag>{map ? enumLabel(map, value) : value}</Tag>;
};

export function AssetDossierPage() {
  const { assetId } = useParams();
  const goBack = useBackNavigate('/assets');
  const [loading, setLoading] = useState(false);
  const [dossier, setDossier] = useState<AssetDossier | null>(null);
  const [qrUrl, setQrUrl] = useState<string>('');
  const [contractOpen, setContractOpen] = useState(false);
  const [contractLoading, setContractLoading] = useState(false);
  const [contractDetail, setContractDetail] = useState<ContractDetail | null>(null);
  const [contractPreviewHtml, setContractPreviewHtml] = useState('');

  const load = async () => {
    if (!assetId) return;
    setLoading(true);
    try {
      const data = await api.get<AssetDossier>(`/assets/${assetId}/dossier`);
      setDossier(data);
      try {
        const qr = await api.get<{ qrCodeUrl: string }>(`/assets/${assetId}/qrcode-url`);
        setQrUrl(qr.qrCodeUrl || data.asset?.qrCodeUrl || '');
      } catch {
        setQrUrl(data.asset?.qrCodeUrl || '');
      }
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载资产档案失败');
      setDossier(null);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [assetId]);

  const asset = dossier?.asset;
  const summary = dossier?.statusSummary;

  const handleOpenContract = async (row: Row) => {
    const id = Number(row.id);
    if (!Number.isFinite(id)) return;
    setContractOpen(true);
    setContractLoading(true);
    setContractDetail(row as ContractDetail);
    setContractPreviewHtml('');
    try {
      const detail = await api.get<ContractDetail>(`/contracts/${id}`);
      setContractDetail(detail);
      try {
        const preview = await api.get<{ previewHtml?: string; contentHtml?: string }>(
          `/contracts/${id}/document/preview`,
        );
        setContractPreviewHtml(
          preview?.previewHtml ?? preview?.contentHtml ?? detail.docHtml ?? '',
        );
      } catch {
        setContractPreviewHtml(detail.docHtml ?? '');
      }
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载合同失败');
    } finally {
      setContractLoading(false);
    }
  };

  const relatedBills = useMemo(() => {
    if (!dossier || !contractDetail?.id) return [];
    return dossier.bills.filter((b) => Number(b.contractId) === Number(contractDetail.id));
  }, [dossier, contractDetail]);

  const relatedVacates = useMemo(() => {
    if (!dossier || !contractDetail?.id) return [];
    return dossier.vacateOrders.filter((v) => Number(v.contractId) === Number(contractDetail.id));
  }, [dossier, contractDetail]);

  const handleDownloadQrcode = async () => {
    if (!assetId) return;
    try {
      const name = await api.download(
        `/assets/${assetId}/qrcode`,
        `asset-${asset?.assetNo ?? assetId}-qrcode.png`,
      );
      message.success(`已下载 ${name}`);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '二维码下载失败');
    }
  };

  const basicItems = useMemo(() => {
    if (!asset) return [];
    return [
      {
        key: 'image',
        label: '资产图片',
        children: asset.imageUrl ? (
          <img
            src={asset.imageUrl}
            alt="资产图片"
            className="h-20 rounded-md border border-[var(--ams-border)] object-cover"
          />
        ) : (
          '-'
        ),
      },
      { key: 'assetNo', label: '资产编号', children: asset.assetNo },
      { key: 'name', label: '资产名称', children: asset.name },
      { key: 'assetType', label: '资产类型', children: enumLabel(ASSET_TYPE, asset.assetType) },
      {
        key: 'assetCompany',
        label: '资产公司',
        children: asset.assetCompanyName ?? asset.assetCompanyId ?? '-',
      },
      {
        key: 'propertyCompany',
        label: '产权公司',
        children: asset.propertyCompanyName ?? asset.propertyCompanyId ?? '-',
      },
      { key: 'project', label: '项目ID', children: asset.projectId ?? '-' },
      { key: 'zone', label: '分区', children: asset.zoneName ?? '-' },
      { key: 'floor', label: '分区楼层', children: asset.floorNo ?? '-' },
      { key: 'area', label: '资产面积(㎡)', children: asset.area ?? '-' },
      { key: 'leaseArea', label: '租赁面积(㎡)', children: asset.leaseArea ?? '-' },
      {
        key: 'partialLease',
        label: '部分租赁状态',
        children: enumLabel(PARTIAL_LEASE_STATUS, asset.partialLeaseStatus),
      },
      { key: 'nature', label: '资产性质', children: enumLabel(ASSET_NATURE, asset.assetNature) },
      { key: 'usage', label: '资产用途', children: enumLabel(ASSET_USAGE, asset.usageType) },
      {
        key: 'houseType',
        label: '资产房型',
        children: enumLabel(ASSET_HOUSE_TYPE, asset.houseType),
      },
      { key: 'source', label: '资产来源', children: enumLabel(ASSET_SOURCE, asset.sourceType) },
      {
        key: 'ownership',
        label: '资产权属',
        children: enumLabel(ASSET_OWNERSHIP, asset.ownershipType),
      },
      {
        key: 'buildingPlan',
        label: '建筑规划',
        children: enumLabel(BUILDING_PLAN, asset.buildingPlan),
      },
      {
        key: 'structureType',
        label: '建筑结构',
        children: enumLabel(BUILDING_STRUCTURE, asset.structureType),
      },
      { key: 'registeredAt', label: '登记入库时间', children: asset.registeredAt ?? '-' },
      { key: 'orig', label: '原值(万元)', children: asset.originalValue ?? '-' },
      {
        key: 'respDept',
        label: '责任部门',
        children: asset.responsibleDepartmentName ?? asset.responsibleDepartmentId ?? '-',
      },
      {
        key: 'respUser',
        label: '责任人',
        children: asset.responsibleUserName ?? asset.responsibleUserId ?? '-',
      },
      {
        key: 'lease',
        label: '租控状态',
        children: statusTag(asset.leaseControlStatus, LEASE_CONTROL_STATUS),
      },
      {
        key: 'structure',
        label: '结构状态',
        children: statusTag(asset.structureStatus, STRUCTURE_STATUS),
      },
      {
        key: 'addr',
        label: '资产坐落',
        children:
          [asset.province, asset.city, asset.district, asset.address].filter(Boolean).join(' ') ||
          '-',
      },
      { key: 'opCo', label: '经营公司', children: asset.operatingCompanyId ?? '-' },
      { key: 'rentA', label: '评估租金', children: asset.baseRentAssessed ?? '-' },
      { key: 'rentF', label: '备案底价', children: asset.baseRentFloor ?? '-' },
      { key: 'rentM', label: '市场参考价', children: asset.marketRefRent ?? '-' },
      { key: 'water', label: '水表号', children: asset.waterMeterNo ?? '-' },
      { key: 'elec', label: '电表号', children: asset.electricMeterNo ?? '-' },
      { key: 'parent', label: '父资产', children: asset.parentAssetId ?? '-' },
      { key: 'oldNo', label: '原编号', children: asset.oldAssetNo ?? '-' },
    ];
  }, [asset]);

  if (!assetId) {
    return <Empty description="缺少资产 ID" />;
  }

  return (
    <div className="space-y-4 min-w-0">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Space wrap>
          <Button icon={<ArrowLeftOutlined />} onClick={goBack} aria-label="返回原页面">
            返回
          </Button>
          <h2 className="text-base font-semibold m-0">
            资产档案
            {asset ? ` · ${asset.assetNo} ${asset.name}` : ''}
          </h2>
        </Space>
        <Space wrap>
          <AssetQuickActions
            asset={asset ? { ...asset, id: asset.id ?? Number(assetId) } : null}
            onSuccess={() => void load()}
          />
          <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>
            刷新
          </Button>
        </Space>
      </div>

      <Card size="small" styles={{ body: { padding: '10px 12px' } }}>
        <AssetQrLabel
          variant="dossier"
          loading={loading && !asset}
          asset={{
            assetNo: asset?.assetNo,
            name: asset?.name,
            assetType: asset?.assetType,
            area: asset?.area,
            leaseControlStatus: asset?.leaseControlStatus ?? summary?.leaseControlStatus,
            address: asset
              ? [asset.province, asset.city, asset.district, asset.address].filter(Boolean).join('')
              : undefined,
            qrUrl,
          }}
          onDownload={qrUrl ? () => void handleDownloadQrcode() : undefined}
        />
      </Card>

      {summary && (
        <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-3">
          <Card size="small">
            <div className="text-gray-500 text-xs mb-1">租控状态</div>
            <div className="font-semibold">
              {enumLabel(LEASE_CONTROL_STATUS, summary.leaseControlStatus)}
            </div>
          </Card>
          <Card size="small">
            <div className="text-gray-500 text-xs mb-1">结构状态</div>
            <div className="font-semibold">
              {enumLabel(STRUCTURE_STATUS, summary.structureStatus)}
            </div>
          </Card>
          <Card size="small">
            <div className="text-gray-500 text-xs mb-1">抵押</div>
            <div className="font-semibold">{summary.mortgaged ? '在押' : '未抵押'}</div>
          </Card>
          <Card size="small">
            <div className="text-gray-500 text-xs mb-1">生效合同</div>
            <div className="font-semibold">{summary.activeContractCount ?? 0}</div>
          </Card>
          <Card size="small">
            <div className="text-gray-500 text-xs mb-1">欠费账单</div>
            <div className="font-semibold text-orange-600">{summary.unpaidBillCount ?? 0}</div>
          </Card>
          <Card size="small">
            <div className="text-gray-500 text-xs mb-1">欠费金额</div>
            <div className="font-semibold text-red-600">
              {Number(summary.arrearsAmount ?? 0).toFixed(2)}
            </div>
          </Card>
        </div>
      )}

      <Card loading={loading} styles={{ body: { paddingTop: 8 } }}>
        {!dossier ? (
          <Empty description="暂无档案数据" />
        ) : (
          <Tabs
            items={[
              {
                key: 'basic',
                label: '基础信息',
                children: (
                  <Descriptions
                    size="small"
                    bordered
                    column={{ xs: 1, sm: 2, lg: 3 }}
                    items={basicItems}
                  />
                ),
              },
              {
                key: 'timeline',
                label: `全链路时间线(${dossier.timeline?.length ?? 0})`,
                children:
                  (dossier.timeline?.length ?? 0) === 0 ? (
                    <Empty description="暂无历史记录" />
                  ) : (
                    <Timeline
                      items={(dossier.timeline ?? []).map((t, idx) => ({
                        key: `${t.category}-${t.bizId}-${idx}`,
                        color: TIMELINE_COLOR[t.category] ?? 'gray',
                        children: (
                          <div className="min-w-0">
                            <div className="flex flex-wrap items-center gap-2">
                              <Tag>{CATEGORY_LABEL[t.category] ?? t.category}</Tag>
                              <span className="font-medium">{t.title}</span>
                              {t.status && <Tag color="processing">{t.status}</Tag>}
                              {t.category === 'contract' && t.bizId != null && (
                                <a
                                  className="text-xs"
                                  onClick={() => {
                                    const row = dossier.contracts.find(
                                      (c) => Number(c.id) === Number(t.bizId),
                                    );
                                    if (row) void handleOpenContract(row);
                                  }}
                                >
                                  查看
                                </a>
                              )}
                            </div>
                            <div className="text-xs text-gray-500 mt-1">
                              {t.occurredAt ?? '-'}
                              {t.remark ? ` · ${t.remark}` : ''}
                            </div>
                          </div>
                        ),
                      }))}
                    />
                  ),
              },
              {
                key: 'lease',
                label: `租控变更(${dossier.leaseControlLogs.length})`,
                children: (
                  <Table
                    size="small"
                    rowKey="id"
                    pagination={{ pageSize: 10 }}
                    dataSource={dossier.leaseControlLogs}
                    columns={[
                      {
                        title: '时间',
                        dataIndex: 'createdAt',
                        width: 180,
                        render: (v) => String(v ?? '-'),
                      },
                      {
                        title: '原状态',
                        dataIndex: 'fromStatus',
                        render: (v) => enumLabel(LEASE_CONTROL_STATUS, String(v ?? '')),
                      },
                      {
                        title: '新状态',
                        dataIndex: 'toStatus',
                        render: (v) => enumLabel(LEASE_CONTROL_STATUS, String(v ?? '')),
                      },
                      { title: '业务类型', dataIndex: 'bizType' },
                      { title: '业务ID', dataIndex: 'bizId', width: 90 },
                      { title: '备注', dataIndex: 'remark', ellipsis: true },
                    ]}
                    scroll={{ x: 800 }}
                  />
                ),
              },
              {
                key: 'contracts',
                label: `合同(${dossier.contracts.length})`,
                children: (
                  <Table
                    size="small"
                    rowKey="id"
                    pagination={{ pageSize: 10 }}
                    dataSource={dossier.contracts}
                    onRow={(row) => ({
                      onClick: () => void handleOpenContract(row),
                      className: 'cursor-pointer',
                    })}
                    columns={[
                      { title: '合同号', dataIndex: 'contractNo' },
                      {
                        title: '状态',
                        dataIndex: 'status',
                        render: (v) => statusTag(String(v ?? ''), CONTRACT_STATUS),
                      },
                      { title: '租户ID', dataIndex: 'tenantId', width: 90 },
                      { title: '起租', dataIndex: 'startDate', width: 120 },
                      { title: '止租', dataIndex: 'endDate', width: 120 },
                      { title: '租金', dataIndex: 'rentAmount', width: 100 },
                      { title: '保证金', dataIndex: 'depositAmount', width: 100 },
                      {
                        title: '操作',
                        key: 'actions',
                        fixed: 'right',
                        width: 100,
                        render: (_: unknown, row: Row) => (
                          <TableActions
                            actions={[
                              {
                                key: 'view',
                                label: '查看',
                                icon: <EyeOutlined />,
                                // 该动作会请求 GET /contracts/{id}（受 contract.ledger:view 校验），
                                // 无权时点开必然 403，故直接隐藏；本页路由是钻取路由，
                                // 不在 PATH_TO_CODE 镜像里，只能写全码
                                perm: 'contract.ledger:view',
                                onClick: () => void handleOpenContract(row),
                              },
                            ]}
                          />
                        ),
                      },
                    ]}
                    scroll={{ x: 960 }}
                    locale={{ emptyText: '暂无合同' }}
                  />
                ),
              },
              {
                key: 'bills',
                label: `账单(${dossier.bills.length})`,
                children: (
                  <Table
                    size="small"
                    rowKey="id"
                    pagination={{ pageSize: 10 }}
                    dataSource={dossier.bills}
                    columns={[
                      { title: '账单号', dataIndex: 'billNo' },
                      { title: '类型', dataIndex: 'billType', width: 90 },
                      {
                        title: '状态',
                        dataIndex: 'status',
                        render: (v) => statusTag(String(v ?? ''), BILL_STATUS),
                      },
                      { title: '应收', dataIndex: 'amount', width: 100 },
                      { title: '已收', dataIndex: 'paidAmount', width: 100 },
                      { title: '到期日', dataIndex: 'dueDate', width: 120 },
                      { title: '催缴等级', dataIndex: 'dunningLevel', width: 90 },
                    ]}
                    scroll={{ x: 900 }}
                  />
                ),
              },
              {
                key: 'dunning',
                label: `催缴(${dossier.dunningRecords.length})`,
                children: (
                  <Table
                    size="small"
                    rowKey="id"
                    pagination={{ pageSize: 10 }}
                    dataSource={dossier.dunningRecords}
                    columns={[
                      { title: '时间', dataIndex: 'createdAt', width: 180 },
                      { title: '等级', dataIndex: 'level', width: 70 },
                      { title: '方式', dataIndex: 'method', width: 100 },
                      { title: '结果', dataIndex: 'result', width: 100 },
                      { title: '内容', dataIndex: 'content', ellipsis: true },
                      { title: '租户反馈', dataIndex: 'tenantFeedback', ellipsis: true },
                    ]}
                    scroll={{ x: 900 }}
                  />
                ),
              },
              {
                key: 'repairs',
                label: `维修(${dossier.repairs.length})`,
                children: (
                  <Table
                    size="small"
                    rowKey="id"
                    pagination={{ pageSize: 10 }}
                    dataSource={dossier.repairs}
                    columns={[
                      { title: 'ID', dataIndex: 'id', width: 70 },
                      {
                        title: '状态',
                        dataIndex: 'status',
                        render: (v) => statusTag(String(v ?? ''), REPAIR_STATUS),
                      },
                      { title: '报修人', dataIndex: 'reporterName', width: 100 },
                      { title: '描述', dataIndex: 'description', ellipsis: true },
                      { title: '完成时间', dataIndex: 'completedAt', width: 180 },
                    ]}
                    scroll={{ x: 800 }}
                  />
                ),
              },
              {
                key: 'inspections',
                label: `巡查(${dossier.inspections.length})`,
                children: (
                  <Table
                    size="small"
                    rowKey="id"
                    pagination={{ pageSize: 10 }}
                    dataSource={dossier.inspections}
                    columns={[
                      { title: '计划日', dataIndex: 'planDate', width: 120 },
                      { title: '状态', dataIndex: 'status', width: 90 },
                      { title: '结果', dataIndex: 'result', width: 100 },
                      { title: '隐患', dataIndex: 'hazardDesc', ellipsis: true },
                      { title: '时间', dataIndex: 'createdAt', width: 180 },
                    ]}
                    scroll={{ x: 700 }}
                  />
                ),
              },
              {
                key: 'certs',
                label: `权证抵押(${dossier.certificates.length + dossier.mortgages.length})`,
                children: (
                  <Space direction="vertical" className="w-full" size="middle">
                    <Table
                      size="small"
                      title={() => '权证'}
                      rowKey="id"
                      pagination={false}
                      dataSource={dossier.certificates}
                      columns={[
                        { title: 'ID', dataIndex: 'id', width: 70 },
                        { title: '证号', dataIndex: 'certNo' },
                        { title: '类型', dataIndex: 'certType' },
                        { title: '产权人', dataIndex: 'ownerName' },
                      ]}
                      locale={{ emptyText: '暂无权证' }}
                    />
                    <Table
                      size="small"
                      title={() => '抵押'}
                      rowKey="id"
                      pagination={false}
                      dataSource={dossier.mortgages}
                      columns={[
                        { title: 'ID', dataIndex: 'id', width: 70 },
                        { title: '抵押权人', dataIndex: 'mortgagee' },
                        { title: '金额', dataIndex: 'amount', width: 100 },
                        {
                          title: '状态',
                          dataIndex: 'status',
                          render: (v) => statusTag(String(v ?? ''), MORTGAGE_STATUS),
                        },
                        {
                          title: '起止',
                          render: (_, r) => `${r.startDate ?? '-'} ~ ${r.endDate ?? '-'}`,
                        },
                      ]}
                      locale={{ emptyText: '暂无抵押' }}
                    />
                  </Space>
                ),
              },
              {
                key: 'ops',
                label: `调拨/处置/占用(${
                  dossier.transfers.length + dossier.disposals.length + dossier.occupations.length
                })`,
                children: (
                  <Space direction="vertical" className="w-full" size="middle">
                    <Table
                      size="small"
                      title={() => '调拨'}
                      rowKey="id"
                      pagination={false}
                      dataSource={dossier.transfers}
                      columns={[
                        { title: 'ID', dataIndex: 'id', width: 70 },
                        {
                          title: '状态',
                          dataIndex: 'status',
                          render: (v) => statusTag(String(v ?? ''), TRANSFER_STATUS),
                        },
                        { title: '调出公司', dataIndex: 'fromCompanyId', width: 100 },
                        { title: '调入公司', dataIndex: 'toCompanyId', width: 100 },
                        { title: '生效日', dataIndex: 'effectiveDate', width: 120 },
                        { title: '原因', dataIndex: 'reason', ellipsis: true },
                      ]}
                      locale={{ emptyText: '暂无调拨' }}
                    />
                    <Table
                      size="small"
                      title={() => '处置'}
                      rowKey="id"
                      pagination={false}
                      dataSource={dossier.disposals}
                      columns={[
                        { title: 'ID', dataIndex: 'id', width: 70 },
                        {
                          title: '状态',
                          dataIndex: 'status',
                          render: (v) => statusTag(String(v ?? ''), DISPOSAL_STATUS),
                        },
                        { title: '方式', dataIndex: 'disposalType', width: 100 },
                        { title: '实际金额', dataIndex: 'actualAmount', width: 100 },
                        { title: '原因', dataIndex: 'reason', ellipsis: true },
                      ]}
                      locale={{ emptyText: '暂无处置' }}
                    />
                    <Table
                      size="small"
                      title={() => '占用'}
                      rowKey="id"
                      pagination={false}
                      dataSource={dossier.occupations}
                      columns={[
                        { title: 'ID', dataIndex: 'id', width: 70 },
                        { title: '状态', dataIndex: 'status', width: 100 },
                        { title: '部门', dataIndex: 'department', width: 120 },
                        {
                          title: '起止',
                          render: (_, r) => `${r.startDate ?? '-'} ~ ${r.endDate ?? '-'}`,
                        },
                        { title: '原因', dataIndex: 'reason', ellipsis: true },
                      ]}
                      locale={{ emptyText: '暂无占用' }}
                    />
                  </Space>
                ),
              },
              {
                key: 'vacate',
                label: `退租(${dossier.vacateOrders.length})`,
                children: (
                  <Table
                    size="small"
                    rowKey="id"
                    pagination={{ pageSize: 10 }}
                    dataSource={dossier.vacateOrders}
                    columns={[
                      { title: 'ID', dataIndex: 'id', width: 70 },
                      { title: '合同ID', dataIndex: 'contractId', width: 90 },
                      {
                        title: '状态',
                        dataIndex: 'status',
                        render: (v) => statusTag(String(v ?? ''), VACATE_STATUS),
                      },
                      { title: '预计退租日', dataIndex: 'expectedVacateDate', width: 120 },
                      { title: '原因', dataIndex: 'reason', ellipsis: true },
                    ]}
                    scroll={{ x: 700 }}
                  />
                ),
              },
              {
                key: 'misc',
                label: `招租/评估/表计/拆合`,
                children: (
                  <Space direction="vertical" className="w-full" size="middle">
                    <Table
                      size="small"
                      title={() => '招租'}
                      rowKey="id"
                      pagination={false}
                      dataSource={dossier.leaseListings}
                      columns={[
                        { title: 'ID', dataIndex: 'id', width: 70 },
                        { title: '状态', dataIndex: 'status', width: 90 },
                        { title: '租金', dataIndex: 'rentAmount', width: 100 },
                        { title: '发布时间', dataIndex: 'publishedAt', width: 180 },
                      ]}
                      locale={{ emptyText: '暂无招租' }}
                    />
                    <Table
                      size="small"
                      title={() => '评估'}
                      rowKey="id"
                      pagination={false}
                      dataSource={dossier.evaluations}
                      columns={[
                        { title: 'ID', dataIndex: 'id', width: 70 },
                        { title: '用途', dataIndex: 'purpose', width: 100 },
                        { title: '状态', dataIndex: 'status', width: 100 },
                        { title: '评估值', dataIndex: 'resultValue', width: 100 },
                        { title: '机构', dataIndex: 'institution', ellipsis: true },
                      ]}
                      locale={{ emptyText: '暂无评估' }}
                    />
                    <Table
                      size="small"
                      title={() => '表计'}
                      rowKey="id"
                      pagination={false}
                      dataSource={dossier.meters}
                      columns={[
                        { title: 'ID', dataIndex: 'id', width: 70 },
                        {
                          title: '类型',
                          dataIndex: 'meterType',
                          render: (v) => enumLabel(METER_TYPE, String(v ?? '')),
                        },
                        { title: '表号', dataIndex: 'meterNo' },
                        { title: '倍率', dataIndex: 'multiplier', width: 80 },
                        {
                          title: '公摊',
                          dataIndex: 'shared',
                          width: 70,
                          render: (v) => (v ? '是' : '否'),
                        },
                      ]}
                      locale={{ emptyText: '暂无表计' }}
                    />
                    <Table
                      size="small"
                      title={() => '拆分合并'}
                      rowKey="id"
                      pagination={false}
                      dataSource={dossier.structureLogs}
                      columns={[
                        { title: 'ID', dataIndex: 'id', width: 70 },
                        { title: '操作', dataIndex: 'opType', width: 90 },
                        { title: '源资产', dataIndex: 'sourceAssetIds' },
                        { title: '结果资产', dataIndex: 'resultAssetIds' },
                        { title: '时间', dataIndex: 'createdAt', width: 180 },
                        { title: '备注', dataIndex: 'remark', ellipsis: true },
                      ]}
                      locale={{ emptyText: '暂无拆合记录' }}
                    />
                    <div className="text-sm text-gray-500">
                      相关入口：
                      <Link className="ml-2" to="/contracts">
                        合同
                      </Link>
                      <Link className="ml-2" to="/bills">
                        账单
                      </Link>
                      <Link className="ml-2" to="/asset-transfers">
                        调拨
                      </Link>
                      <Link className="ml-2" to="/assets/structure-logs">
                        拆合日志
                      </Link>
                    </div>
                  </Space>
                ),
              },
            ]}
          />
        )}
      </Card>

      <Modal
        title={
          contractDetail
            ? `合同详情 · ${contractDetail.contractNo ?? contractDetail.id}`
            : '合同详情'
        }
        open={contractOpen}
        onCancel={() => {
          setContractOpen(false);
          setContractDetail(null);
          setContractPreviewHtml('');
        }}
        footer={
          <Button
            onClick={() => {
              setContractOpen(false);
              setContractDetail(null);
              setContractPreviewHtml('');
            }}
          >
            关闭
          </Button>
        }
        destroyOnClose
        centered
        width={Math.min(800, typeof window !== 'undefined' ? window.innerWidth - 32 : 800)}
        styles={{ body: { maxHeight: '70vh', overflowY: 'auto', paddingTop: 12 } }}
        zIndex={1100}
      >
        {contractLoading && !contractDetail ? (
          <div className="text-gray-400 py-10 text-center">加载中…</div>
        ) : contractDetail ? (
          <div className="space-y-4">
            <Descriptions column={{ xs: 1, sm: 2 }} size="small" bordered>
              <Descriptions.Item label="合同编号">
                {contractDetail.contractNo ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label="状态">
                {statusTag(String(contractDetail.status ?? ''), CONTRACT_STATUS)}
              </Descriptions.Item>
              <Descriptions.Item label="租户ID">{contractDetail.tenantId ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="版本">{contractDetail.version ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="起租日">
                {contractDetail.startDate ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label="到期日">{contractDetail.endDate ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="租赁面积">
                {contractDetail.leaseArea ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label="租赁模式">
                {String(contractDetail.leaseMode ?? '-')}
              </Descriptions.Item>
              <Descriptions.Item label="租金类型">
                {enumLabel(RENT_TYPE, contractDetail.rentType)}
              </Descriptions.Item>
              <Descriptions.Item label="缴费周期">
                {enumLabel(PAYMENT_CYCLE, contractDetail.paymentCycle)}
              </Descriptions.Item>
              <Descriptions.Item label="周期租金">
                {contractDetail.rentAmount ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label="保证金">
                {contractDetail.depositAmount ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label="预交">
                {contractDetail.prepayAmount ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label="免租天数">
                {contractDetail.freeRentDays ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label="缴费状态">
                {String(contractDetail.paymentStatus ?? '-')}
              </Descriptions.Item>
              <Descriptions.Item label="电子签">
                {String(contractDetail.esignStatus ?? '-')}
              </Descriptions.Item>
              <Descriptions.Item label="备注" span={2}>
                {contractDetail.remark ?? '-'}
              </Descriptions.Item>
            </Descriptions>

            <div>
              <div className="text-sm font-medium mb-2">关联账单（{relatedBills.length}）</div>
              <Table
                size="small"
                rowKey="id"
                pagination={false}
                dataSource={relatedBills}
                columns={[
                  { title: '账单号', dataIndex: 'billNo', ellipsis: true },
                  {
                    title: '状态',
                    dataIndex: 'status',
                    width: 90,
                    render: (v) => statusTag(String(v ?? ''), BILL_STATUS),
                  },
                  { title: '应收', dataIndex: 'amount', width: 90 },
                  { title: '已收', dataIndex: 'paidAmount', width: 90 },
                  { title: '到期日', dataIndex: 'dueDate', width: 110 },
                ]}
                locale={{ emptyText: '无关联账单' }}
                scroll={{ x: 480 }}
              />
            </div>

            {relatedVacates.length > 0 && (
              <div>
                <div className="text-sm font-medium mb-2">退租单（{relatedVacates.length}）</div>
                <Table
                  size="small"
                  rowKey="id"
                  pagination={false}
                  dataSource={relatedVacates}
                  columns={[
                    {
                      title: '状态',
                      dataIndex: 'status',
                      width: 100,
                      render: (v) => statusTag(String(v ?? ''), VACATE_STATUS),
                    },
                    { title: '原因', dataIndex: 'reason', ellipsis: true },
                    { title: '结算金额', dataIndex: 'settlementAmount', width: 100 },
                  ]}
                  scroll={{ x: 400 }}
                />
              </div>
            )}

            {contractPreviewHtml ? (
              <div>
                <div className="text-sm font-medium mb-2">合同正文预览</div>
                <iframe
                  title="合同预览"
                  srcDoc={contractPreviewHtml}
                  className="w-full border border-[var(--ams-border)] rounded bg-gray-50"
                  style={{ height: 360 }}
                />
              </div>
            ) : (
              <div className="text-xs text-gray-400">暂无已生成的合同 Word 正文</div>
            )}
          </div>
        ) : (
          <Empty description="暂无合同数据" />
        )}
      </Modal>
    </div>
  );
}

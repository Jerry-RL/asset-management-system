import { Button, QRCode, Tag, Typography } from 'antd';
import { CopyOutlined, DownloadOutlined, QrcodeOutlined } from '@ant-design/icons';
import { ASSET_TYPE, LEASE_CONTROL_STATUS, enumLabel } from '@/lib/labels';

export interface AssetQrInfo {
  assetNo?: string;
  name?: string;
  assetType?: string;
  area?: number | string;
  leaseControlStatus?: string;
  address?: string;
  qrUrl: string;
}

interface AssetQrLabelProps {
  asset: AssetQrInfo;
  /** modal：弹窗竖版标签；dossier：档案横版卡片 */
  variant?: 'modal' | 'dossier';
  loading?: boolean;
  onDownload?: () => void;
}

/** 一产一码展示标签：二维码 + 资产摘要，台账弹窗与档案页共用 */
export function AssetQrLabel({ asset, variant = 'modal', loading, onDownload }: AssetQrLabelProps) {
  const address = asset.address || [asset.assetNo, asset.name].filter(Boolean).join(' · ') || '—';
  const statusLabel = enumLabel(LEASE_CONTROL_STATUS, asset.leaseControlStatus);
  const typeLabel = enumLabel(ASSET_TYPE, asset.assetType);
  const qrSize = variant === 'dossier' ? 72 : 200;

  if (loading) {
    return (
      <div
        className={`flex items-center justify-center text-[var(--ams-text-secondary)] ${
          variant === 'dossier' ? 'py-3' : 'py-16'
        }`}
      >
        加载中…
      </div>
    );
  }

  if (!asset.qrUrl) {
    return (
      <div
        className={`flex items-center justify-center text-[var(--ams-text-secondary)] ${
          variant === 'dossier' ? 'py-3' : 'py-16'
        }`}
      >
        暂无二维码
      </div>
    );
  }

  if (variant === 'dossier') {
    return (
      <div className="flex items-center gap-3 min-w-0">
        <div className="shrink-0 rounded border border-[var(--ams-border)] bg-white p-1">
          <QRCode value={asset.qrUrl} size={qrSize} errorLevel="M" bordered={false} />
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2 mb-1">
            <span className="inline-flex items-center gap-1 text-xs font-medium text-[var(--ams-primary)]">
              <QrcodeOutlined />
              一产一码
            </span>
            <span className="text-xs text-[var(--ams-text-secondary)]">手机扫码查看招租信息</span>
          </div>
          <Typography.Paragraph
            copyable={{ icon: <CopyOutlined />, tooltips: ['复制链接', '已复制'] }}
            ellipsis={{ rows: 1, tooltip: asset.qrUrl }}
            className="!mb-0 text-xs text-[var(--ams-text-secondary)]"
          >
            {asset.qrUrl}
          </Typography.Paragraph>
        </div>
        {onDownload ? (
          <Button
            size="small"
            icon={<DownloadOutlined />}
            onClick={onDownload}
            aria-label="下载二维码"
            className="shrink-0"
          >
            下载
          </Button>
        ) : null}
      </div>
    );
  }

  const meta = (
    <div className="min-w-0 flex-1 space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="inline-flex items-center gap-1 rounded px-2 py-0.5 text-xs font-medium bg-blue-50 text-[var(--ams-primary)]">
          <QrcodeOutlined />
          一产一码
        </span>
        {asset.leaseControlStatus ? <Tag className="!m-0">{statusLabel}</Tag> : null}
        {asset.assetType ? <Tag className="!m-0">{typeLabel}</Tag> : null}
      </div>

      <div>
        <div className="text-xs text-[var(--ams-text-secondary)] mb-0.5">资产编号</div>
        <div className="font-semibold text-base tracking-wide">{asset.assetNo || '—'}</div>
      </div>

      <div>
        <div className="text-xs text-[var(--ams-text-secondary)] mb-0.5">资产名称</div>
        <div className="font-medium text-[15px] leading-snug break-words">{asset.name || '—'}</div>
      </div>

      <div className="grid grid-cols-2 gap-3 text-sm">
        <div>
          <div className="text-xs text-[var(--ams-text-secondary)] mb-0.5">面积</div>
          <div>{asset.area != null && asset.area !== '' ? `${asset.area} ㎡` : '—'}</div>
        </div>
        <div className="min-w-0">
          <div className="text-xs text-[var(--ams-text-secondary)] mb-0.5">坐落</div>
          <div className="truncate" title={address}>
            {address}
          </div>
        </div>
      </div>

      <div>
        <div className="text-xs text-[var(--ams-text-secondary)] mb-1">扫码链接</div>
        <Typography.Paragraph
          copyable={{ icon: <CopyOutlined />, tooltips: ['复制链接', '已复制'] }}
          ellipsis={{ rows: 2, tooltip: asset.qrUrl }}
          className="!mb-0 text-xs text-[var(--ams-text-secondary)] break-all"
        >
          {asset.qrUrl}
        </Typography.Paragraph>
      </div>

      {onDownload ? (
        <Button
          type="primary"
          icon={<DownloadOutlined />}
          onClick={onDownload}
          aria-label="下载二维码"
        >
          下载 PNG
        </Button>
      ) : null}
    </div>
  );

  return (
    <div className="rounded-lg border border-[var(--ams-border)] bg-[var(--ams-bg)] p-4">
      <div className="flex flex-col items-center gap-4">
        <div className="shrink-0 flex flex-col items-center gap-2">
          <div className="rounded-lg border border-[var(--ams-border)] bg-white p-3 shadow-sm">
            <QRCode value={asset.qrUrl} size={qrSize} errorLevel="M" bordered={false} />
          </div>
          <div className="text-[11px] text-[var(--ams-text-secondary)] text-center leading-relaxed max-w-[200px]">
            手机扫描查看资产招租信息
          </div>
        </div>
        <div className="w-full border-t border-dashed border-[var(--ams-border)] pt-4">{meta}</div>
      </div>
    </div>
  );
}

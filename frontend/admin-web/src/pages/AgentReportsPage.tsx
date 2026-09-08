import { useEffect, useState } from 'react';
import { Button, Card, Space, Table, Tag, message } from 'antd';
import { DownloadOutlined, ReloadOutlined } from '@ant-design/icons';
import { api } from '@/lib/api';
import { AGENT_REPORT_STATUS, enumLabel } from '@/lib/labels';

interface AgentReport {
  id: number;
  title?: string;
  format?: string;
  status?: string;
  verified?: boolean;
  createdAt?: string;
}

interface DownloadPayload {
  reportId: number;
  title?: string;
  fileName?: string;
  contentHtml?: string;
  verified?: boolean;
}

export function AgentReportsPage() {
  const [list, setList] = useState<AgentReport[]>([]);
  const [loading, setLoading] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const rows = await api.get<AgentReport[]>('/intelligence/reports');
      setList(rows ?? []);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const handleDownload = async (reportId: number) => {
    try {
      const data = await api.post<DownloadPayload>(`/intelligence/reports/${reportId}/download`, {});
      const html = data?.contentHtml ?? '';
      const blob = new Blob(
        [
          `<!DOCTYPE html><html><head><meta charset="utf-8"/><title>${data?.title ?? 'report'}</title></head><body>${html}</body></html>`,
        ],
        { type: 'text/html;charset=utf-8' },
      );
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = data?.fileName ?? `agent-report-${reportId}.html`;
      a.click();
      URL.revokeObjectURL(url);
      message.success('已导出 HTML');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '导出失败');
    }
  };

  return (
    <div className="p-4 space-y-4">
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <div>
          <h1 className="text-xl font-semibold m-0">Agent 报告</h1>
          <p className="text-gray-500 text-sm m-0 mt-1">核验通过后可下载 HTML 报告</p>
        </div>
        <Button icon={<ReloadOutlined />} onClick={load}>
          刷新
        </Button>
      </div>
      <Card size="small">
        <Table
          rowKey="id"
          loading={loading}
          dataSource={list}
          pagination={{ pageSize: 10 }}
          columns={[
            { title: 'ID', dataIndex: 'id', width: 80 },
            { title: '标题', dataIndex: 'title', ellipsis: true },
            { title: '格式', dataIndex: 'format', width: 80 },
            {
              title: '状态',
              dataIndex: 'status',
              width: 110,
              render: (v: string) => <Tag>{enumLabel(AGENT_REPORT_STATUS, v)}</Tag>,
            },
            {
              title: '已核验',
              dataIndex: 'verified',
              width: 90,
              render: (v: boolean) => (v ? '是' : '否'),
            },
            { title: '创建时间', dataIndex: 'createdAt', width: 180 },
            {
              title: '操作',
              key: 'actions',
              width: 140,
              render: (_: unknown, row: AgentReport) => (
                <Space>
                  <Button
                    type="primary"
                    size="small"
                    icon={<DownloadOutlined />}
                    disabled={!row.verified}
                    aria-label="下载 HTML"
                    onClick={() => handleDownload(row.id)}
                  >
                    下载 HTML
                  </Button>
                </Space>
              ),
            },
          ]}
        />
      </Card>
    </div>
  );
}

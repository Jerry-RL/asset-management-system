import { useEffect, useState } from 'react';
import { Button, Card, Space, Table, Tag, message } from 'antd';
import { CheckOutlined, ReloadOutlined } from '@ant-design/icons';
import { api, type PageResult } from '@/lib/api';
import { confirmDangerous } from '@/lib/confirm';
import { CHANNEL, PAYMENT_CONFIRM, PAYMENT_METHOD, enumLabel } from '@/lib/labels';

interface Payment {
  id: number;
  paymentNo?: string;
  contractId?: number;
  amount?: number;
  method?: string;
  channel?: string;
  confirmStatus?: string;
  paidAt?: string;
}

export function PaymentConfirmPage() {
  const [list, setList] = useState<Payment[]>([]);
  const [loading, setLoading] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const raw = await api.get<PageResult<Payment> | Payment[]>(
        '/payments?page=1&pageSize=100&confirmStatus=pending',
      );
      const rows = Array.isArray(raw) ? raw : (raw?.list ?? []);
      setList(rows);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const handleConfirm = (paymentId: number) => {
    confirmDangerous({
      title: '确认到账',
      content: `确定确认收款单 #${paymentId} 已到账吗？确认后将核销相关账单。`,
      okText: '确定到账',
      okType: 'primary',
      onOk: async () => {
        try {
          await api.post(`/payments/${paymentId}/confirm`, {});
          message.success('已确认到账');
          await load();
        } catch (e) {
          message.error(e instanceof Error ? e.message : '确认失败');
          throw e;
        }
      },
    });
  };

  return (
    <div className="p-4 space-y-4">
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <div>
          <h1 className="text-xl font-semibold m-0">现场收款待确认</h1>
          <p className="text-gray-500 text-sm m-0 mt-1">确认现金/转账等到账后核销账单</p>
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
            { title: '收款单号', dataIndex: 'paymentNo', width: 160 },
            { title: '合同ID', dataIndex: 'contractId', width: 100 },
            { title: '金额', dataIndex: 'amount', width: 100 },
            {
              title: '方式',
              dataIndex: 'method',
              width: 100,
              render: (v: string) => enumLabel(PAYMENT_METHOD, v),
            },
            {
              title: '来源',
              dataIndex: 'channel',
              width: 100,
              render: (v: string) => enumLabel(CHANNEL, v),
            },
            {
              title: '状态',
              dataIndex: 'confirmStatus',
              width: 100,
              render: (v: string) => (
                <Tag color="warning">{enumLabel(PAYMENT_CONFIRM, v || 'pending')}</Tag>
              ),
            },
            { title: '收款时间', dataIndex: 'paidAt', ellipsis: true },
            {
              title: '操作',
              key: 'actions',
              width: 120,
              render: (_: unknown, row: Payment) => (
                <Space>
                  <Button
                    type="primary"
                    size="small"
                    icon={<CheckOutlined />}
                    aria-label="确认到账"
                    onClick={() => handleConfirm(row.id)}
                  >
                    确认到账
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

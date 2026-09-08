import { useEffect, useState } from 'react';
import { Button, Card, Input, Space, Table, Tabs, Tag, message } from 'antd';
import { CheckOutlined, CloseOutlined, ReloadOutlined } from '@ant-design/icons';
import { api } from '@/lib/api';
import { confirmDangerous } from '@/lib/confirm';
import { APPROVAL_STATUS, BIZ_TYPE, TASK_STATUS, enumLabel } from '@/lib/labels';

interface InboxRow {
  taskId: number;
  instanceId: number;
  nodeId: string;
  taskStatus: string;
  bizType?: string;
  bizId?: number;
  instanceStatus?: string;
  submittedBy?: number;
  submittedAt?: string;
  currentNode?: string;
}

interface ApprovalInstance {
  id: number;
  bizType: string;
  bizId: number;
  status: string;
  currentNode?: string;
  submittedBy?: number;
  submittedAt?: string;
}

export function ApprovalPage() {
  const [inbox, setInbox] = useState<InboxRow[]>([]);
  const [instances, setInstances] = useState<ApprovalInstance[]>([]);
  const [loading, setLoading] = useState(false);
  const [comment, setComment] = useState('同意');

  const loadInbox = async () => {
    setLoading(true);
    try {
      const rows = await api.get<InboxRow[]>('/approvals/inbox');
      setInbox(rows ?? []);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载待办失败');
    } finally {
      setLoading(false);
    }
  };

  const loadInstances = async () => {
    try {
      const rows = await api.get<ApprovalInstance[]>('/approvals/instances');
      setInstances(rows ?? []);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载实例失败');
    }
  };

  useEffect(() => {
    loadInbox();
    loadInstances();
  }, []);

  const handleApprove = (instanceId: number) => {
    confirmDangerous({
      title: '确认通过',
      content: `确定通过审批实例 #${instanceId} 吗？`,
      okText: '确定通过',
      okType: 'primary',
      onOk: async () => {
        try {
          await api.post(`/approvals/${instanceId}/approve`, { comment: comment || '同意' });
          message.success('已通过');
          await Promise.all([loadInbox(), loadInstances()]);
        } catch (e) {
          message.error(e instanceof Error ? e.message : '审批失败');
          throw e;
        }
      },
    });
  };

  const handleReject = (instanceId: number) => {
    confirmDangerous({
      title: '确认驳回',
      content: `确定驳回审批实例 #${instanceId} 吗？驳回后需重新发起。`,
      okText: '确定驳回',
      onOk: async () => {
        try {
          await api.post(`/approvals/${instanceId}/reject`, { comment: comment || '驳回' });
          message.success('已驳回');
          await Promise.all([loadInbox(), loadInstances()]);
        } catch (e) {
          message.error(e instanceof Error ? e.message : '驳回失败');
          throw e;
        }
      },
    });
  };

  return (
    <div className="p-4 space-y-4">
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <div>
          <h1 className="text-xl font-semibold m-0">审批中心</h1>
          <p className="text-gray-500 text-sm m-0 mt-1">待办办理与审批实例查询</p>
        </div>
        <Space>
          <Input
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            placeholder="审批意见"
            style={{ width: 200 }}
            aria-label="审批意见"
          />
          <Button
            icon={<ReloadOutlined />}
            onClick={() => {
              loadInbox();
              loadInstances();
            }}
          >
            刷新
          </Button>
        </Space>
      </div>

      <Tabs
        items={[
          {
            key: 'inbox',
            label: `我的待办 (${inbox.length})`,
            children: (
              <Card size="small">
                <Table
                  rowKey="taskId"
                  loading={loading}
                  dataSource={inbox}
                  pagination={{ pageSize: 10 }}
                  columns={[
                    { title: '任务ID', dataIndex: 'taskId', width: 90 },
                    { title: '实例ID', dataIndex: 'instanceId', width: 90 },
                    {
                      title: '业务类型',
                      dataIndex: 'bizType',
                      width: 140,
                      render: (v: string) => enumLabel(BIZ_TYPE, v),
                    },
                    { title: '业务单号', dataIndex: 'bizId', width: 100 },
                    { title: '节点', dataIndex: 'nodeId', width: 120 },
                    {
                      title: '状态',
                      dataIndex: 'taskStatus',
                      width: 100,
                      render: (v: string) => (
                        <Tag color="processing">{enumLabel(TASK_STATUS, v)}</Tag>
                      ),
                    },
                    { title: '提交人', dataIndex: 'submittedBy', width: 90 },
                    { title: '提交时间', dataIndex: 'submittedAt', ellipsis: true },
                    {
                      title: '操作',
                      key: 'actions',
                      fixed: 'right',
                      width: 180,
                      render: (_: unknown, row: InboxRow) => (
                        <Space>
                          <Button
                            type="primary"
                            size="small"
                            icon={<CheckOutlined />}
                            aria-label="通过"
                            onClick={() => handleApprove(row.instanceId)}
                          >
                            通过
                          </Button>
                          <Button
                            danger
                            size="small"
                            icon={<CloseOutlined />}
                            aria-label="驳回"
                            onClick={() => handleReject(row.instanceId)}
                          >
                            驳回
                          </Button>
                        </Space>
                      ),
                    },
                  ]}
                  scroll={{ x: 1000 }}
                />
              </Card>
            ),
          },
          {
            key: 'instances',
            label: '审批实例',
            children: (
              <Card size="small">
                <Table
                  rowKey="id"
                  dataSource={instances}
                  pagination={{ pageSize: 10 }}
                  columns={[
                    { title: '实例ID', dataIndex: 'id', width: 90 },
                    {
                      title: '业务类型',
                      dataIndex: 'bizType',
                      width: 140,
                      render: (v: string) => enumLabel(BIZ_TYPE, v),
                    },
                    { title: '业务单号', dataIndex: 'bizId', width: 100 },
                    {
                      title: '状态',
                      dataIndex: 'status',
                      width: 110,
                      render: (v: string) => {
                        const color =
                          v === 'approved' ? 'success' : v === 'rejected' ? 'error' : 'processing';
                        return <Tag color={color}>{enumLabel(APPROVAL_STATUS, v)}</Tag>;
                      },
                    },
                    { title: '当前节点', dataIndex: 'currentNode', width: 120 },
                    { title: '提交人', dataIndex: 'submittedBy', width: 90 },
                    { title: '提交时间', dataIndex: 'submittedAt', ellipsis: true },
                  ]}
                />
              </Card>
            ),
          },
        ]}
      />
    </div>
  );
}

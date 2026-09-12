import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Button, Card, Space, Table, Tag, message } from 'antd';
import {
  ArrowRightOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { api } from '@/lib/api';
import { TableActions, actionsColumnWidth } from '@/components/TableActions';

interface DunningQueueItem {
  billId: number;
  billNo?: string;
  contractId?: number;
  tenantId?: number;
  dueDate?: string;
  overdueDays?: number;
  amount?: number;
  arrears?: number;
  status?: string;
  currentLevel?: number;
  suggestedLevel?: number;
  phase?: string;
}

interface DunningTask {
  id: number;
  taskType?: string;
  refId?: number;
  refNo?: string;
  deadline?: string;
  status?: string;
}

interface AutoJobResult {
  scanned?: number;
  upgraded?: number;
  preDueReminded?: number;
  tasksCreated?: number;
  smsSent?: number;
  highlights?: string[];
}

const LEVEL_COLOR: Record<number, string> = {
  1: 'blue',
  2: 'orange',
  3: 'gold',
  4: 'magenta',
  5: 'red',
};

const TASK_TYPE_LABEL: Record<string, string> = {
  dunning_l1: 'L1 到期提醒',
  dunning_l2: 'L2 催缴单张贴',
  dunning_l3: 'L3 律师函',
  dunning_l4: 'L4 法务督办',
  dunning_l5: 'L5 清退督办',
};

export function DunningAutoPage() {
  const [queue, setQueue] = useState<DunningQueueItem[]>([]);
  const [tasks, setTasks] = useState<DunningTask[]>([]);
  const [loading, setLoading] = useState(false);
  const [running, setRunning] = useState(false);
  const [lastResult, setLastResult] = useState<AutoJobResult | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [q, t] = await Promise.all([
        api.get<DunningQueueItem[]>('/dunning/queue'),
        api.get<DunningTask[]>('/dunning/auto-tasks'),
      ]);
      setQueue(q ?? []);
      setTasks(t ?? []);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const handleAutoRun = async () => {
    setRunning(true);
    try {
      const result = await api.post<AutoJobResult>('/dunning/auto-run');
      setLastResult(result);
      const summary =
        result.highlights?.[0] ??
        `升级 ${result.upgraded ?? 0}，新建任务 ${result.tasksCreated ?? 0}`;
      message.success(`自动催缴完成：${summary}`);
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '执行失败');
    } finally {
      setRunning(false);
    }
  };

  return (
    <div className="space-y-4 min-w-0 max-w-full overflow-hidden">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-lg font-semibold text-gray-900 flex items-center gap-2 m-0">
            <ThunderboltOutlined className="text-[var(--ams-primary)]" />
            自动化催缴
          </h1>
          <p className="text-sm text-gray-500 mt-1 mb-0">
            到期前提醒 → L1–L5 升级 → 自动生成任务中心待办（短信/张贴/法务/清退）
          </p>
        </div>
        <Space wrap>
          <Button icon={<ReloadOutlined />} onClick={() => void load()} aria-label="刷新">
            刷新
          </Button>
          <Button
            type="primary"
            icon={<PlayCircleOutlined />}
            loading={running}
            onClick={() => void handleAutoRun()}
          >
            立即执行自动催缴
          </Button>
          <Link to="/dunning/records" className="text-sm text-[var(--ams-primary)]">
            催缴记录
          </Link>
        </Space>
      </div>

      <div className="grid grid-cols-2 md:grid-cols-5 gap-3">
        {[
          { label: '队列笔数', value: queue.length },
          { label: '待办任务', value: tasks.length },
          { label: '上次升级', value: lastResult?.upgraded ?? '-' },
          { label: '新建任务', value: lastResult?.tasksCreated ?? '-' },
          { label: '短信触达', value: lastResult?.smsSent ?? '-' },
        ].map((s) => (
          <Card key={s.label} size="small" className="shadow-none">
            <div className="text-xs text-gray-500">{s.label}</div>
            <div className="text-xl font-semibold mt-1">{s.value}</div>
          </Card>
        ))}
      </div>

      <Card title="催缴队列（即将到期 / 已逾期）" size="small">
        <Table
          rowKey="billId"
          size="small"
          loading={loading}
          dataSource={queue}
          pagination={{ pageSize: 10 }}
          scroll={{ x: 900 }}
          columns={[
            {
              title: '账单',
              dataIndex: 'billNo',
              render: (v, r) => v || `#${r.billId}`,
            },
            {
              title: '阶段',
              dataIndex: 'phase',
              width: 90,
              render: (v) =>
                v === 'pre_due' ? <Tag color="cyan">即将到期</Tag> : <Tag color="red">已逾期</Tag>,
            },
            { title: '到期日', dataIndex: 'dueDate', width: 120 },
            {
              title: '逾期天',
              dataIndex: 'overdueDays',
              width: 80,
              render: (v) => (v > 0 ? v : '-'),
            },
            {
              title: '欠费',
              dataIndex: 'arrears',
              width: 110,
              render: (v) => (v != null ? `¥${Number(v).toLocaleString()}` : '-'),
            },
            {
              title: '当前等级',
              dataIndex: 'currentLevel',
              width: 90,
              render: (v) =>
                v ? <Tag color={LEVEL_COLOR[v] ?? 'default'}>L{v}</Tag> : <Tag>未催</Tag>,
            },
            {
              title: '建议等级',
              dataIndex: 'suggestedLevel',
              width: 90,
              render: (v) => <Tag color={LEVEL_COLOR[v] ?? 'default'}>L{v}</Tag>,
            },
            {
              title: '合同',
              dataIndex: 'contractId',
              width: 100,
              render: (v) =>
                v ? (
                  <Link to={`/contracts/${v}`} className="text-[var(--ams-primary)]">
                    #{v}
                  </Link>
                ) : (
                  '-'
                ),
            },
          ]}
        />
      </Card>

      <Card title="自动化催缴待办（任务中心）" size="small">
        <Table
          rowKey="id"
          size="small"
          loading={loading}
          dataSource={tasks}
          pagination={{ pageSize: 8 }}
          columns={[
            {
              title: '类型',
              dataIndex: 'taskType',
              render: (v) => TASK_TYPE_LABEL[v] ?? v,
            },
            { title: '单号/说明', dataIndex: 'refNo' },
            {
              title: '账单',
              dataIndex: 'refId',
              width: 100,
              render: (v) => (v ? `#${v}` : '-'),
            },
            { title: '截止时间', dataIndex: 'deadline', width: 180 },
            {
              title: '操作',
              width: actionsColumnWidth(['去处理']),
              render: () => (
                <TableActions
                  actions={[
                    {
                      key: 'handle',
                      label: '去处理',
                      icon: <ArrowRightOutlined />,
                      to: '/tasks',
                    },
                  ]}
                />
              ),
            },
          ]}
        />
      </Card>
    </div>
  );
}

import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Space,
  Spin,
  message,
} from 'antd';
import { ArrowLeftOutlined, SaveOutlined } from '@ant-design/icons';
import { api } from '@/lib/api';
import { RecordSheetSections } from '@/components/RecordSheetSections';
import { useBackNavigate } from '@/lib/navigation';
import { PermissionGuard } from '@/lib/perm';
import {
  loadRecordSheet,
  recordSheetPath,
  saveRecordSheet,
  type RecordSheetPayload,
} from '@/lib/recordSheet';

// ============================================================================
// 分区详情 / 编辑页（设计 §6.3）。路由 /projects/:projectId/zones/:zoneId，钻取页不挂侧边栏。
//
// 上半：分区基本信息（走已上线的 PUT /projects/{pid}/zones/{zoneId}）
// 下半：后续记录三模块（走 /projects/{pid}/zones/{zoneId}/record-sheet）
//
// 两块**各自独立保存**：分区本体与后续记录是两件事，塞进一次交互会让「只想改备注」
// 也走一遍记录 diff（设计 §6.3）。
//
// 两个保存动作都要求 asset.project:update（后端两处的 @RequiresPerm 口径一致），
// 因此按钮用 PermissionGuard 门控，避免用户填完才在最后一步吃 403。
// ============================================================================

interface ZoneRow {
  id: number;
  projectId: number;
  name?: string;
  code?: string;
  sort?: number;
  remark?: string;
  assetCount?: number;
  assetArea?: number;
}

export default function ZoneDetailPage() {
  const { projectId, zoneId } = useParams();
  const goBack = useBackNavigate(`/projects/${projectId}/edit`);

  const [form] = Form.useForm();
  const [zone, setZone] = useState<ZoneRow | null>(null);
  const [loading, setLoading] = useState(true);
  const [savingZone, setSavingZone] = useState(false);
  const [savingSheet, setSavingSheet] = useState(false);
  const [sheetLoading, setSheetLoading] = useState(false);
  const [recordSheet, setRecordSheet] = useState<RecordSheetPayload | null>(null);

  const pid = Number(projectId);
  const zid = Number(zoneId);

  // 分区列表已上线，按 id 过滤即可；不为单条分区新增端点（设计 §6.3）
  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    api
      .get<ZoneRow[]>(`/projects/${pid}/zones`)
      .then((rows) => {
        if (cancelled) return;
        const hit = (rows ?? []).find((item) => item.id === zid) ?? null;
        setZone(hit);
        if (hit) {
          form.setFieldsValue({
            name: hit.name,
            code: hit.code,
            sort: hit.sort,
            remark: hit.remark,
          });
        }
      })
      .catch((e) => {
        if (!cancelled) message.error(e instanceof Error ? e.message : '加载分区失败');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [pid, zid, form]);

  useEffect(() => {
    let cancelled = false;
    setSheetLoading(true);
    loadRecordSheet(recordSheetPath('zone', zid, pid))
      .then((sheet) => {
        if (cancelled) return;
        setRecordSheet({
          receives: sheet.receives,
          sourceInfo: sheet.sourceInfo,
          disposalRecords: sheet.disposalRecords,
        });
      })
      .catch((e) => {
        if (!cancelled) message.error(e instanceof Error ? e.message : '加载后续记录失败');
      })
      .finally(() => {
        if (!cancelled) setSheetLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [pid, zid]);

  const handleSaveZone = async () => {
    try {
      const values = await form.validateFields();
      setSavingZone(true);
      await api.put(`/projects/${pid}/zones/${zid}`, values);
      message.success('分区信息已保存');
    } catch (e) {
      if (e && typeof e === 'object' && 'errorFields' in e) return;
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSavingZone(false);
    }
  };

  const handleSaveSheet = async () => {
    if (!recordSheet) return;
    try {
      setSavingSheet(true);
      // 存回来的值必须覆盖本地：服务端会回填记录 id 与附件 fileName/url，
      // 不覆盖的话下一次保存会把新记录又当成「新增」重复插入。
      const saved = await saveRecordSheet(recordSheetPath('zone', zid, pid), recordSheet);
      setRecordSheet({
        receives: saved.receives,
        sourceInfo: saved.sourceInfo,
        disposalRecords: saved.disposalRecords,
      });
      message.success('后续记录已保存');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSavingSheet(false);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center py-24">
        <Spin size="large" />
      </div>
    );
  }

  if (!zone) {
    return <Alert type="warning" showIcon message="分区不存在或已被删除" />;
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <Space>
          <Button icon={<ArrowLeftOutlined />} onClick={goBack} aria-label="返回原页面">
            返回
          </Button>
          <h2 className="text-base font-semibold m-0">分区详情 · {zone.name}</h2>
        </Space>
      </div>

      <Card title="分区信息" className="border border-[var(--ams-border)]">
        <Form form={form} layout="vertical">
          <div className="grid gap-4 md:grid-cols-2">
            <Form.Item
              name="name"
              label="分区名称"
              rules={[{ required: true, message: '请输入分区名称' }]}
            >
              <Input />
            </Form.Item>
            <Form.Item name="code" label="分区编码">
              <Input />
            </Form.Item>
            <Form.Item name="sort" label="排序">
              <InputNumber className="w-full" min={0} />
            </Form.Item>
            <Form.Item name="remark" label="备注">
              <Input />
            </Form.Item>
          </div>
        </Form>
        {/* 资产面积 / 宗数为只读汇总，不由本页维护 */}
        <Descriptions column={2} size="small">
          <Descriptions.Item label="资产面积">{zone.assetArea ?? 0}</Descriptions.Item>
          <Descriptions.Item label="资产宗数">{zone.assetCount ?? 0}</Descriptions.Item>
        </Descriptions>
        <div className="flex justify-end mt-3">
          <PermissionGuard perm="asset.project:update">
            <Button
              type="primary"
              icon={<SaveOutlined />}
              loading={savingZone}
              onClick={() => void handleSaveZone()}
            >
              保存分区信息
            </Button>
          </PermissionGuard>
        </div>
      </Card>

      <Card title="后续记录" className="border border-[var(--ams-border)]" loading={sheetLoading}>
        <RecordSheetSections
          ownerType="zone"
          ownerId={zid}
          projectId={pid}
          value={recordSheet}
          onChange={setRecordSheet}
        />
        <div className="flex justify-end mt-3">
          <PermissionGuard perm="asset.project:update">
            <Button
              type="primary"
              icon={<SaveOutlined />}
              loading={savingSheet}
              onClick={() => void handleSaveSheet()}
            >
              保存后续记录
            </Button>
          </PermissionGuard>
        </div>
      </Card>
    </div>
  );
}

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Button, Modal, Spin, message } from 'antd';
import { SaveOutlined } from '@ant-design/icons';
import { RecordSheetSections } from '@/components/RecordSheetSections';
import { PermissionGuard, usePerm } from '@/lib/perm';
import {
  loadOwnerSheet,
  recordSheetPath,
  saveOwnerSheet,
  type RecordOwnerType,
  type RecordSheetPayload,
} from '@/lib/recordSheet';

/**
 * 后续记录编辑器（弹窗）。
 *
 * <p>为列表页提供「不离开当前列表就能改后续记录」的入口：项目管理 / 资产台账的操作栏、
 * 以及项目分区管理页的分区工具栏都复用它。三处的读写契约完全一致，差异只有归属类型与权限码。
 *
 * <p><strong>为什么必须先读回整张 sheet 再提交</strong>：保存是**全量 diff** 语义
 * （未出现在请求体里的记录与附件会被服务端软删）。所以本组件在打开时先拉回完整聚合
 * （{@link loadOwnerSheet} —— 资产侧还会把 disposal_order 上的处置单合并进来），
 * 编辑后把整张回写（{@link saveOwnerSheet}）。只提交「改动的那一条」会静默删掉其它记录。
 */
export interface RecordSheetModalProps {
  open: boolean;
  ownerType: RecordOwnerType;
  /** 归属对象 id（项目 / 资产 / 分区）。为空时弹窗按不可用处理。 */
  ownerId?: number | null;
  /** 分区必传：用于拼 record-sheet 路径（后端用路由形态排斥「分区不属于该项目」） */
  projectId?: number | null;
  /** 透传给 ActorField 收窄员工搜索范围；缺省则全局搜人 */
  companyId?: number | null;
  departmentId?: number | null;
  /** 保存所需权限码，与后端 `@RequiresPerm` 同源（如 `asset.project:update`） */
  savePerm: string;
  /** 归属对象名称，显示在标题里，便于确认正在改哪一条 */
  subjectLabel?: string;
  onClose: () => void;
}

export function RecordSheetModal({
  open,
  ownerType,
  ownerId,
  projectId,
  companyId,
  departmentId,
  savePerm,
  subjectLabel,
  onClose,
}: RecordSheetModalProps) {
  const [sheet, setSheet] = useState<RecordSheetPayload | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  /** 失败重试信号：递增即触发重新拉取 */
  const [reloadToken, setReloadToken] = useState(0);
  // 资产处置单需要 operation.disposal:create；无该权限时面板是只读的（见 lib/recordSheet.ts）
  const can = usePerm();

  /**
   * `recordSheetPath` 在「分区缺 projectId」时会直接抛错：这里先算一次兜住，
   * 缺条件就按「不可用」处理，而不是让整页崩掉。
   */
  const available = useMemo(() => {
    if (ownerId == null) return false;
    try {
      recordSheetPath(ownerType, ownerId, projectId ?? undefined);
      return true;
    } catch {
      return false;
    }
  }, [ownerType, ownerId, projectId]);

  /**
   * 打开即拉取。依赖 `open` 而不是靠 `destroyOnHidden` 重新挂载：
   * 同一个归属对象被反复打开时也要拿到**服务端最新**的记录，否则会用上一次的旧快照做全量 diff。
   */
  useEffect(() => {
    if (!open || !available || ownerId == null) {
      setSheet(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setLoadError(null);
    loadOwnerSheet(ownerType, ownerId, projectId ?? undefined)
      .then((loaded) => {
        if (cancelled) return;
        // 显式挑五块业务数据：读视图将来多出字段时不至于被原样回写进请求体
        setSheet({
          receives: loaded.receives,
          sourceInfo: loaded.sourceInfo,
          disposalRecords: loaded.disposalRecords,
          costRecords: loaded.costRecords,
          evaluations: loaded.evaluations,
        });
      })
      .catch((e) => {
        if (cancelled) return;
        setLoadError(e instanceof Error ? e.message : '加载后续记录失败');
        setSheet(null);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [open, available, ownerType, ownerId, projectId, reloadToken]);

  const handleSave = useCallback(async () => {
    if (!sheet || !available || ownerId == null) return;
    try {
      setSaving(true);
      /** 存回来的值必须覆盖本地：服务端会回填记录 id、附件 fileName/url 与处置单状态，
       *  不覆盖的话下一次保存会把新记录又当成「新增」重复插入。 */
      const saved = await saveOwnerSheet(ownerType, ownerId, sheet, {
        projectId: projectId ?? undefined,
        canSyncDisposals: can('operation.disposal', 'create'),
      });
      setSheet({
        receives: saved.receives,
        sourceInfo: saved.sourceInfo,
        disposalRecords: saved.disposalRecords,
        costRecords: saved.costRecords,
        evaluations: saved.evaluations,
      });
      message.success('后续记录已保存');
      onClose();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSaving(false);
    }
  }, [sheet, available, ownerId, ownerType, projectId, can, onClose]);

  return (
    <Modal
      open={open}
      onCancel={onClose}
      title={subjectLabel ? `后续记录 · ${subjectLabel}` : '后续记录'}
      width={960}
      // 半填状态误点遮罩会直接丢掉刚录入的记录，只允许显式取消 / 关闭
      maskClosable={false}
      destroyOnHidden
      // 表单很长：只滚弹窗主体，标题与底部按钮常驻
      styles={{ body: { maxHeight: '68vh', overflowY: 'auto' } }}
      footer={
        <div className="flex justify-end gap-2">
          <Button onClick={onClose}>取消</Button>
          {/* 无保存权限时不渲染按钮，而不是渲染成禁用 —— 与页面里其它写操作口径一致 */}
          <PermissionGuard perm={savePerm}>
            <Button
              type="primary"
              icon={<SaveOutlined />}
              loading={saving}
              disabled={!sheet || loading}
              onClick={() => void handleSave()}
            >
              保存后续记录
            </Button>
          </PermissionGuard>
        </div>
      }
    >
      {loading ? (
        <div className="flex items-center justify-center py-16">
          <Spin />
        </div>
      ) : loadError ? (
        <Alert
          type="error"
          showIcon
          message="后续记录加载失败"
          description={loadError}
          action={
            <Button size="small" onClick={() => setReloadToken((token) => token + 1)}>
              重试
            </Button>
          }
        />
      ) : !available ? (
        <Alert type="warning" showIcon message="缺少归属对象，无法读取后续记录" />
      ) : (
        <RecordSheetSections
          ownerType={ownerType}
          ownerId={ownerId}
          projectId={projectId}
          companyId={companyId}
          departmentId={departmentId}
          value={sheet}
          onChange={setSheet}
        />
      )}
    </Modal>
  );
}

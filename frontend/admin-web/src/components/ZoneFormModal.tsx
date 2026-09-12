import { useEffect } from 'react';
import { Form, Input, InputNumber, Modal } from 'antd';
import type { ProjectZone } from '@/lib/projectZones';

export interface ZoneFormModalProps {
  /** null = 关闭；`{ name: '' }` = 新增；带 id 的对象 = 编辑 */
  editing: ProjectZone | null;
  /** 提交中：由 useProjectZones().saving 驱动 */
  submitting: boolean;
  onCancel: () => void;
  /** 校验通过后回调；提交与报错由调用方负责 */
  onSubmit: (values: ProjectZone) => void;
}

/**
 * 分区新增/编辑弹窗（设计 §6.3）。
 *
 * <p>两个入口共用：项目列表展开行（{@link ProjectZonesPanel}）与「项目分区管理」页。
 * 共用是刻意的 —— 排序缺省提示、资产面积/资产数**不进表单**（只读、由后端汇总）这两条口径
 * 一旦各写一份，必然出现「一个入口能填面积、另一个不能」的分叉。
 */
export function ZoneFormModal({ editing, submitting, onCancel, onSubmit }: ZoneFormModalProps) {
  const [form] = Form.useForm();

  /**
   * 回填：`editing` 每次打开都是新对象（新增是字面量、编辑来自列表行），故 effect 必定重跑。
   * `forceRender` 让 Form 常驻挂载，setFieldsValue 不会因未连接而告警。
   */
  useEffect(() => {
    if (!editing) return;
    form.setFieldsValue({
      name: editing.name ?? '',
      code: editing.code ?? '',
      sort: editing.sort,
      remark: editing.remark ?? '',
    });
  }, [editing, form]);

  /** 校验通过才回调；校验失败时 antd 已在字段上标红，这里静默返回 */
  const handleOk = async () => {
    try {
      const values = (await form.validateFields()) as ProjectZone;
      onSubmit(values);
    } catch {
      /* 校验失败：不回调 */
    }
  };

  return (
    <Modal
      title={editing?.id != null ? '编辑分区' : '新增分区'}
      open={!!editing}
      // forceRender：Form 常驻挂载，回填不因未连接而告警
      forceRender
      onCancel={onCancel}
      onOk={() => void handleOk()}
      confirmLoading={submitting}
      width={Math.min(480, typeof window !== 'undefined' ? window.innerWidth - 32 : 480)}
    >
      <Form form={form} layout="vertical" className="mt-2">
        <Form.Item
          name="name"
          label="分区名称"
          rules={[{ required: true, message: '请填写分区名称' }]}
        >
          <Input placeholder="如 A区" />
        </Form.Item>
        <Form.Item name="code" label="分区编码">
          <Input placeholder="如 A" />
        </Form.Item>
        <Form.Item
          name="sort"
          label="排序"
          extra={editing?.id != null ? '留空表示保持原排序' : '留空表示追加到末尾'}
        >
          <InputNumber className="w-full" />
        </Form.Item>
        <Form.Item name="remark" label="备注">
          <Input.TextArea rows={2} />
        </Form.Item>
      </Form>
    </Modal>
  );
}

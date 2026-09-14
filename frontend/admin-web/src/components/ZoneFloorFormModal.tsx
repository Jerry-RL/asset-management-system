import { useEffect } from 'react';
import { Form, Input, InputNumber, Modal } from 'antd';
import type { ProjectZoneFloor } from '@/lib/projectZoneFloors';

export interface ZoneFloorFormModalProps {
  /** null = 关闭；`{ floorNo: <下一个楼层号> }` = 新增；带 id 的对象 = 编辑 */
  editing: ProjectZoneFloor | null;
  /** 提交中：由 useProjectZoneFloors().saving 驱动 */
  submitting: boolean;
  onCancel: () => void;
  /** 校验通过后回调；提交与报错由调用方负责 */
  onSubmit: (values: ProjectZoneFloor) => void;
}

/**
 * 楼层新增/编辑弹窗（V50）。
 *
 * <p>刻意**不提供面积与资产数输入**：两者都由后端按 `asset.floor_no` 汇总
 * （与分区面积同口径），一旦进表单就会出现「人工填的面积」与「资产实际面积」两套数字。
 *
 * <p>唯一有副作用的字段是**楼层号**：改号时后端会同步该分区内资产的 `floor_no`
 * （见 ProjectZoneFloorService#update），因此这里把这条后果写在字段说明里，
 * 避免使用者在有资产的楼层上无意改号。
 */
export function ZoneFloorFormModal({
  editing,
  submitting,
  onCancel,
  onSubmit,
}: ZoneFloorFormModalProps) {
  const [form] = Form.useForm();

  /**
   * 回填：`editing` 每次打开都是新对象（新增是字面量、编辑来自列表行），故 effect 必定重跑。
   * `forceRender` 让 Form 常驻挂载，setFieldsValue 不会因未连接而告警。
   */
  useEffect(() => {
    if (!editing) return;
    form.setFieldsValue({
      floorNo: editing.floorNo,
      name: editing.name ?? '',
      remark: editing.remark ?? '',
    });
  }, [editing, form]);

  /** 校验通过才回调；校验失败时 antd 已在字段上标红，这里静默返回 */
  const handleOk = async () => {
    try {
      const values = (await form.validateFields()) as ProjectZoneFloor;
      onSubmit(values);
    } catch {
      /* 校验失败：不回调 */
    }
  };

  const isEdit = editing?.id != null;

  return (
    <Modal
      title={isEdit ? '编辑楼层' : '新增楼层'}
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
          name="floorNo"
          label="楼层号"
          rules={[{ required: true, message: '请填写楼层号' }]}
          extra={isEdit ? '改号会同步该分区内资产的楼层' : '地下层填负数，如 -1 表示 B1'}
        >
          <InputNumber className="w-full" precision={0} placeholder="如 3" />
        </Form.Item>
        <Form.Item name="name" label="楼层名称" extra="留空则显示为「3F」">
          <Input placeholder="如 3F 商业" />
        </Form.Item>
        <Form.Item name="remark" label="备注">
          <Input.TextArea rows={2} />
        </Form.Item>
      </Form>
    </Modal>
  );
}

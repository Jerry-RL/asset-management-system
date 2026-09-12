import { useMemo, useState, type ReactNode } from 'react';
import { Button, Form, Input, InputNumber, Modal, Select, Space, Switch, message } from 'antd';
import {
  FileAddOutlined,
  NotificationOutlined,
  ToolOutlined,
  AuditOutlined,
} from '@ant-design/icons';
import { api } from '@/lib/api';
import { ASSET_QUICK_ACTIONS } from '@/lib/assetQuickActions';
import type { FieldConfig, RowActionConfig } from '@/components/ResourcePage';

type Row = Record<string, unknown>;
type Option = { value: string | number; label: string };

const ACTION_ICON: Record<string, ReactNode> = {
  createContract: <FileAddOutlined />,
  publishListing: <NotificationOutlined />,
  createRepair: <ToolOutlined />,
  createInspection: <AuditOutlined />,
};

const loadFieldOptions = async (field: FieldConfig): Promise<Option[]> => {
  if (!field.optionsPath) return field.options ?? [];
  const raw = await api.get<unknown>(field.optionsPath);
  let list: Row[] = [];
  if (Array.isArray(raw)) list = raw as Row[];
  else if (raw && typeof raw === 'object') {
    const obj = raw as { list?: Row[]; records?: Row[] };
    list = obj.list ?? obj.records ?? [];
  }
  const vk = field.optionsValueKey ?? 'id';
  const lk = field.optionsLabelKey ?? 'name';
  const ek = field.optionsLabelExtraKey;
  return list.map((item) => {
    const value = item[vk] as string | number;
    const base = String(item[lk] ?? value);
    const extra = ek && item[ek] != null ? `（${String(item[ek])}）` : '';
    return { value, label: `${base}${extra}` };
  });
};

const renderFormFields = (fields: FieldConfig[], dynamicOptions?: Record<string, Option[]>) =>
  fields.map((f) => {
    const options = dynamicOptions?.[f.name] ?? f.options ?? [];
    return (
      <Form.Item
        key={f.name}
        name={f.name}
        label={f.label}
        rules={f.required ? [{ required: true, message: `请填写${f.label}` }] : undefined}
        valuePropName={f.type === 'boolean' ? 'checked' : 'value'}
      >
        {f.type === 'select' || f.optionsPath ? (
          <Select
            allowClear
            options={options.map((o) => ({ value: o.value, label: o.label }))}
            placeholder="请选择"
            showSearch
            optionFilterProp="label"
          />
        ) : f.type === 'textarea' ? (
          <Input.TextArea rows={3} />
        ) : f.type === 'boolean' ? (
          <Switch />
        ) : f.type === 'number' ? (
          <InputNumber className="w-full" />
        ) : (
          <Input type={f.type === 'date' ? 'date' : 'text'} />
        )}
      </Form.Item>
    );
  });

interface AssetQuickActionsProps {
  asset: Row | null | undefined;
  onSuccess?: () => void;
}

export function AssetQuickActions({ asset, onSuccess }: AssetQuickActionsProps) {
  const [action, setAction] = useState<RowActionConfig | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [fieldOptions, setFieldOptions] = useState<Record<string, Option[]>>({});
  const [form] = Form.useForm();

  const visibleActions = useMemo(() => {
    if (!asset) return [];
    return ASSET_QUICK_ACTIONS.filter((a) => !a.visible || a.visible(asset));
  }, [asset]);

  const handleOpen = async (next: RowActionConfig) => {
    if (!asset) return;
    setAction(next);
    form.setFieldsValue(next.defaultValues?.(asset) ?? {});
    const nextOptions: Record<string, Option[]> = {};
    await Promise.all(
      next.fields.map(async (f) => {
        if (!f.optionsPath) return;
        try {
          nextOptions[f.name] = await loadFieldOptions(f);
        } catch {
          nextOptions[f.name] = f.options ?? [];
        }
      }),
    );
    setFieldOptions(nextOptions);
  };

  const handleClose = () => {
    setAction(null);
    form.resetFields();
    setFieldOptions({});
  };

  const handleSubmit = async () => {
    if (!action || !asset) return;
    const id = asset.id as number;
    if (id == null) return;
    setSubmitting(true);
    try {
      const values = await form.validateFields();
      const body: Record<string, unknown> = { ...values };
      (action.omitFields ?? []).forEach((k) => {
        delete body[k];
      });
      if (action.injectIdField) body[action.injectIdField] = id;
      const path =
        typeof action.submitPath === 'function'
          ? action.submitPath(id, asset)
          : action.submitPath.replace(':id', String(id));
      const created =
        action.method === 'put'
          ? ((await api.put(path, body)) as Row)
          : ((await api.post(path, body)) as Row);

      if (
        action.followUp &&
        (!action.followUp.when || action.followUp.when(values, created ?? {}))
      ) {
        const followPath = action.followUp.path(created ?? {}, values);
        const followBody = action.followUp.body?.(created ?? {}, values) ?? {};
        if (action.followUp.method === 'put') await api.put(followPath, followBody);
        else await api.post(followPath, followBody);
        message.success(action.followUp.successMessage ?? action.successMessage ?? '操作成功');
      } else {
        message.success(action.successMessage ?? '操作成功');
      }
      handleClose();
      onSuccess?.();
    } catch (e) {
      if (e && typeof e === 'object' && 'errorFields' in e) return;
      message.error(e instanceof Error ? e.message : '操作失败');
    } finally {
      setSubmitting(false);
    }
  };

  if (!asset || visibleActions.length === 0) return null;

  return (
    <>
      <Space wrap>
        {visibleActions.map((a) => (
          <Button
            key={a.key}
            icon={ACTION_ICON[a.key]}
            onClick={() => void handleOpen(a)}
            aria-label={a.label}
          >
            {a.label}
          </Button>
        ))}
      </Space>

      <Modal
        title={action?.title ?? action?.label}
        open={!!action}
        onCancel={handleClose}
        onOk={() => void handleSubmit()}
        confirmLoading={submitting}
        destroyOnClose
        centered
        width={Math.min(520, typeof window !== 'undefined' ? window.innerWidth - 32 : 520)}
        styles={{ body: { maxHeight: '60vh', overflowY: 'auto' } }}
      >
        {action && (
          <Form form={form} layout="vertical" className="mt-2">
            {renderFormFields(action.fields, fieldOptions)}
          </Form>
        )}
      </Modal>
    </>
  );
}

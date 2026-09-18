import { useEffect, useState } from 'react';
import { Alert, Form, Modal, message } from 'antd';
import { ResourceFormField } from '@/components/ResourcePage';
import { LISTING_PUBLISH_DEFAULTS, listingPublishFields } from '@/lib/listingFields';
import { api } from '@/lib/api';

/** 发布招租表单值（与 `listingPublishFields` 的字段一一对应）。 */
export interface ListingPublishFormValues {
  assetId?: number;
  coverImage?: { url: string; fileId?: number } | null;
  detailImages?: { url: string; fileId?: number }[];
  rentType?: string;
  annualRent?: number;
  rentNegotiable?: boolean;
  recommended?: boolean;
  sortNo?: number;
  intro?: string;
  remark?: string;
}

interface PublishListingModalProps {
  open: boolean;
  /** 从资产行内发起时传入：隐藏「资产」选择并把 assetId 注入表单 */
  assetId?: number;
  /** 行内发起时的资产展示文案（编号 + 名称），仅用于提示 */
  assetLabel?: string;
  /**
   * 已驳回的招租：传 `id` 即走「重新提交」端点，并把 `values` 回填到表单。
   * 为空表示新建发布。
   */
  resubmit?: { id: number; values: ListingPublishFormValues } | null;
  onClose: () => void;
  onSuccess: () => void;
}

/**
 * 发布招租弹窗（FR-LEASE-001/002，V59）。
 *
 * <p>「发布」= 提交发布审批：提交后招租为待审批，租控不变、小程序端不可见；
 * 审批中心通过后才变为招租中并出现在小程序端。
 *
 * <p>字段渲染复用 {@link ResourceFormField}（与 `ResourcePage` 同一份规则），
 * 避免图片 / 远程下拉这些控件在这里出现第二套实现。
 */
export function PublishListingModal({
  open,
  assetId,
  assetLabel,
  resubmit,
  onClose,
  onSuccess,
}: PublishListingModalProps) {
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);

  // 行内发起（有 assetId）不再让用户选资产：需求明确「在列表中操作无需选择」
  const fields = listingPublishFields({ withAsset: assetId == null });
  const isResubmit = resubmit != null;

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    form.setFieldsValue({
      ...LISTING_PUBLISH_DEFAULTS,
      ...(resubmit?.values ?? {}),
      assetId: resubmit?.values.assetId ?? assetId,
    });
  }, [open, assetId, resubmit, form]);

  const handleSubmit = async () => {
    let values: ListingPublishFormValues;
    try {
      values = (await form.validateFields()) as ListingPublishFormValues;
    } catch {
      // 校验失败由表单内联提示，这里不额外弹 toast
      return;
    }
    setSubmitting(true);
    try {
      const body = { ...values, assetId: values.assetId ?? assetId };
      if (isResubmit) {
        await api.post(`/lease-listings/${resubmit.id}/resubmit`, body);
        message.success('招租已重新提交审批');
      } else {
        await api.post('/lease-listings', body);
        message.success('招租发布已提交审批，通过后小程序端可见');
      }
      onSuccess();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '提交失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      title={isResubmit ? '重新提交招租发布' : '发布招租信息'}
      width={720}
      okText="提交审批"
      cancelText="取消"
      confirmLoading={submitting}
      onOk={handleSubmit}
      onCancel={onClose}
      destroyOnHidden
    >
      <Alert
        type="info"
        showIcon
        className="mb-4"
        message={
          isResubmit
            ? '修改后重新提交，将再次进入「招租发布审批」。'
            : '提交后进入「招租发布审批」，审批通过后小程序端可见；驳回时须填写原因。'
        }
      />
      {assetId != null && assetLabel && (
        <div className="mb-4 text-sm text-gray-600">
          发布资产：<span className="font-medium text-gray-900">{assetLabel}</span>
        </div>
      )}
      <Form form={form} layout="vertical">
        {fields.map((field) => (
          <ResourceFormField key={field.name} field={field} form={form} />
        ))}
      </Form>
    </Modal>
  );
}

import { useEffect, useMemo, useState } from 'react';
import { Modal, Upload, message } from 'antd';
import type { UploadFile, UploadProps } from 'antd/es/upload';
import { LoadingOutlined, PlusOutlined } from '@ant-design/icons';
import { beforeUploadImage, uploadFile } from '@/lib/upload';
import type { ImageValue } from './ImageUploadField';

// ============================================================================
// 多图上传字段：缩略图网格 + 点击预览。
// 与 ImageUploadField 同构的受控组件，直接作为 <Form.Item name="xxx"> 的子节点使用：
//   <Form.Item name="detailImages"><MultiImageUploadField bizType="lease_listing" /></Form.Item>
// 表单值形态：Array<{ url: string; fileId?: number }>（缺省视为空数组）
//
// 为什么单独做一个组件而不是给 ImageUploadField 加 multiple：
// 单图的值是 {url,fileId} | null，多图是数组 —— 同一个 props 承载两种形态会让
// 「删除最后一张」在单图语义下是 null、多图语义下是 []，调用点必须知道自己在哪一支。
// ============================================================================

interface MultiImageUploadFieldProps {
  /** 受控值：由 Form.Item 注入 */
  value?: ImageValue[] | null;
  /** 值变更回调：由 Form.Item 注入 */
  onChange?: (value: ImageValue[]) => void;
  /** 后端附件业务类型（如 lease_listing） */
  bizType: string;
  /** 预览弹层标题 */
  previewTitle?: string;
  /** 单张图片大小上限(MB) */
  maxMB?: number;
  /** 最多几张 */
  maxCount?: number;
}

export function MultiImageUploadField({
  value,
  onChange,
  bizType,
  previewTitle = '图片预览',
  maxMB = 5,
  maxCount = 9,
}: MultiImageUploadFieldProps) {
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [uploading, setUploading] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [previewImage, setPreviewImage] = useState('');

  // 只有 value 引用变化才重算，避免 effect 每次渲染都执行
  const items = useMemo(() => value ?? [], [value]);

  // 外部值（详情回填 / 删除）→ 上传列表。
  // 列表内容一致时返回原引用：setFileList 以同一引用 setState 会被 React 丢弃，
  // 从而不会打断正在上传的那一项的进度状态。
  useEffect(() => {
    setFileList((prev) => {
      const unchanged =
        prev.length === items.length && prev.every((f, i) => f.url === items[i].url);
      if (unchanged) return prev;
      return items.map((item, index) => ({
        uid: String(item.fileId ?? `image-${index}`),
        name: '图片',
        status: 'done' as const,
        url: item.url,
      }));
    });
  }, [items]);

  const handleUpload: UploadProps['customRequest'] = async ({ file, onSuccess, onError }) => {
    try {
      setUploading(true);
      const data = await uploadFile(file as File, bizType);
      // 表单值是唯一真相：antd 的 onSuccess 只会写 file.response，不会写 file.url，
      // 缩略图要靠这里回写的 url 才渲染得出来（与 ImageUploadField 同口径）。
      onChange?.([...items, { url: data.url, fileId: data.fileId }]);
      onSuccess?.(data);
      message.success('图片上传成功');
    } catch (e) {
      onError?.(e as Error);
      message.error(e instanceof Error ? e.message : '图片上传失败');
    } finally {
      setUploading(false);
    }
  };

  const handleChange: UploadProps['onChange'] = ({ fileList: next }) => {
    setFileList(next);
  };

  const handleRemove: UploadProps['onRemove'] = (file) => {
    // 以表单值为准做删除：按 url 过滤而不是按 uid —— 回填项的 uid 与 fileId 不同源，
    // 且同一张图可能被上传两次（不同 fileId、相同 url），按 uid 删会错行。
    onChange?.(items.filter((item) => item.url !== file.url));
    return true;
  };

  const handlePreview: UploadProps['onPreview'] = (file) => {
    setPreviewImage(file.url ?? '');
    setPreviewOpen(true);
  };

  return (
    <>
      <Upload
        accept="image/*"
        listType="picture-card"
        maxCount={maxCount}
        fileList={fileList}
        beforeUpload={(file) => beforeUploadImage(file, maxMB)}
        customRequest={handleUpload}
        onChange={handleChange}
        onRemove={handleRemove}
        onPreview={handlePreview}
      >
        {fileList.length >= maxCount ? null : (
          <div className="flex flex-col items-center gap-1">
            {uploading ? <LoadingOutlined /> : <PlusOutlined />}
            <span className="text-xs">{uploading ? '上传中' : '上传图片'}</span>
          </div>
        )}
      </Upload>
      <Modal
        open={previewOpen}
        title={previewTitle}
        footer={null}
        onCancel={() => setPreviewOpen(false)}
      >
        {previewImage && (
          <img alt={previewTitle} className="w-full rounded-md" src={previewImage} />
        )}
      </Modal>
    </>
  );
}

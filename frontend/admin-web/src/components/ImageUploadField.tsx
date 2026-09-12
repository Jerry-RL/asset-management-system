import { useEffect, useState } from 'react';
import { Modal, Upload, message } from 'antd';
import type { UploadFile, UploadProps } from 'antd/es/upload';
import { LoadingOutlined, PlusOutlined } from '@ant-design/icons';
import { beforeUploadImage, uploadFile } from '@/lib/upload';

// ============================================================================
// 单图上传字段：缩略图 + 点击预览
// 设计为受控组件，直接作为 <Form.Item name="xxx"> 的子节点使用：
//   <Form.Item name="image"><ImageUploadField bizType="project" /></Form.Item>
// 表单值形态：{ url: string; fileId?: number } | null
// ============================================================================

export interface ImageValue {
  url: string;
  fileId?: number;
}

interface ImageUploadFieldProps {
  /** 受控值：由 Form.Item 注入 */
  value?: ImageValue | null;
  /** 值变更回调：由 Form.Item 注入 */
  onChange?: (value: ImageValue | null) => void;
  /** 后端附件业务类型（如 project / asset） */
  bizType: string;
  /** 预览弹层标题 */
  previewTitle?: string;
  /** 单张图片大小上限(MB) */
  maxMB?: number;
}

export function ImageUploadField({
  value,
  onChange,
  bizType,
  previewTitle = '图片预览',
  maxMB = 5,
}: ImageUploadFieldProps) {
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [uploading, setUploading] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [previewImage, setPreviewImage] = useState('');

  const valueUrl = value?.url ?? '';
  const valueFileId = value?.fileId;

  // 外部值（编辑回填 / 清空）→ 上传列表；URL 未变则保持原列表，避免打断上传中状态
  useEffect(() => {
    if (!valueUrl) {
      setFileList([]);
      return;
    }
    setFileList((prev) => {
      if (prev.length === 1 && prev[0].url === valueUrl) return prev;
      return [
        {
          uid: String(valueFileId ?? 'image'),
          name: '图片',
          status: 'done',
          url: valueUrl,
        },
      ];
    });
  }, [valueUrl, valueFileId]);

  const handleUpload: UploadProps['customRequest'] = async ({ file, onSuccess, onError }) => {
    try {
      setUploading(true);
      const data = await uploadFile(file as File, bizType);
      onChange?.({ url: data.url, fileId: data.fileId });
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
    // 删除图片时同步清空表单值，避免残留旧附件
    if (next.length === 0) onChange?.(null);
  };

  const handlePreview: UploadProps['onPreview'] = (file) => {
    setPreviewImage(file.url ?? valueUrl);
    setPreviewOpen(true);
  };

  return (
    <>
      <Upload
        accept="image/*"
        listType="picture-card"
        maxCount={1}
        fileList={fileList}
        beforeUpload={(file) => beforeUploadImage(file, maxMB)}
        customRequest={handleUpload}
        onChange={handleChange}
        onPreview={handlePreview}
      >
        {fileList.length >= 1 ? null : (
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

import { useRef, useState } from 'react';
import { Button, Upload, message } from 'antd';
import type { UploadProps } from 'antd/es/upload';
import { DeleteOutlined, DownloadOutlined, UploadOutlined } from '@ant-design/icons';
import { uploadFile } from '@/lib/upload';

// ============================================================================
// 多附件上传字段（受控）。设计见 docs/superpowers/specs/2026-09-12-record-forms-design.md §6.4。
//
// 直接作为 <Form.Item name="xxx"> 的子节点使用：
//   <Form.Item name="attachments"><AttachmentField bizType="asset" /></Form.Item>
// 表单值形态：{ fileId: number; url: string; name: string }[]
//
// 与 ImageUploadField 的分工：图片单图用后者（卡片缩略图 + 预览弹层），
// 这里用于「交接文件 / 现场文件 / 来源附件 / 处置附件」这类非图片或批量附件。
// 两者并存，**不要**把 ImageUploadField 改成多文件。
// ============================================================================

/** 表单值形态：一个附件槽位。fileId 是唯一身份，服务端按它做全量 diff。 */
export interface AttachmentValue {
  fileId: number;
  url: string;
  name: string;
}

interface AttachmentFieldProps {
  /** 受控值：由 Form.Item 注入 */
  value?: AttachmentValue[];
  /** 值变更回调：由 Form.Item 注入 */
  onChange?: (value: AttachmentValue[]) => void;
  /**
   * 传给 POST /files/upload 的 bizType（上传来源标记）。
   * 注意它与 biz_attachment.biz_type（附件属于哪个字段）是两回事，后者由服务端决定。
   */
  bizType: string;
  /** 单文件大小上限(MB) */
  maxMB?: number;
  /** 单字段最多几个附件 */
  maxCount?: number;
  accept?: string;
}

/**
 * 类型白名单匹配：支持 `.pdf`（后缀）、`image/*`（主类型通配）、`application/pdf`（精确）。
 * 逗号分隔，逐条取或。不传 accept 表示不限制。
 */
const acceptsFile = (file: File, accept?: string): boolean => {
  if (!accept) return true;
  const rules = accept
    .split(',')
    .map((rule) => rule.trim().toLowerCase())
    .filter(Boolean);
  const fileName = file.name.toLowerCase();
  const fileType = (file.type || '').toLowerCase();
  return rules.some((rule) => {
    if (rule.startsWith('.')) return fileName.endsWith(rule);
    if (rule.endsWith('/*')) return fileType.startsWith(rule.slice(0, -1));
    return fileType === rule;
  });
};

export function AttachmentField({
  value,
  onChange,
  bizType,
  maxMB = 5,
  maxCount = 10,
  accept,
}: AttachmentFieldProps) {
  const items = value ?? [];
  const [uploading, setUploading] = useState(false);

  // 同一批多选（multiple）会在一次交互里并发发起多个 customRequest，它们各自闭包里的
  // items 都是同一份旧值 —— 只靠闭包累加会丢掉除最后一个以外的全部文件。
  // 用 ref 让每次上传都基于「上一次已确认的结果」追加；组件重新渲染时再与受控值对齐，
  // 以覆盖外部清空 / 回填的情况。
  const itemsRef = useRef(items);
  itemsRef.current = items;

  /** 大小与类型前置校验：不合规返回 Upload.LIST_IGNORE，不发起请求。 */
  const handleBeforeUpload = (file: File) => {
    const current = itemsRef.current;
    if (current.length >= maxCount) {
      message.error(`最多上传 ${maxCount} 个附件`);
      return Upload.LIST_IGNORE;
    }
    if (!acceptsFile(file, accept)) {
      message.error(`只允许上传：${accept}`);
      return Upload.LIST_IGNORE;
    }
    if (file.size > maxMB * 1024 * 1024) {
      message.error(`文件大小不能超过 ${maxMB}MB`);
      return Upload.LIST_IGNORE;
    }
    return true;
  };

  const handleUpload: UploadProps['customRequest'] = async ({ file, onSuccess, onError }) => {
    try {
      setUploading(true);
      const data = await uploadFile(file as File, bizType);
      // 追加而不是替换：多附件是累加语义
      const next = [
        ...itemsRef.current,
        { fileId: data.fileId, url: data.url, name: (file as File).name },
      ];
      itemsRef.current = next;
      onChange?.(next);
      onSuccess?.(data);
    } catch (e) {
      onError?.(e as Error);
      message.error(e instanceof Error ? e.message : '附件上传失败');
    } finally {
      setUploading(false);
    }
  };

  /** 删除：按 fileId 过滤。服务端在下一次提交时对未出现的行做软删。 */
  const handleRemove = (fileId: number) => {
    onChange?.(itemsRef.current.filter((item) => item.fileId !== fileId));
  };

  const handleDownload = (item: AttachmentValue) => {
    if (!item.url) {
      message.warning('该附件没有可访问地址');
      return;
    }
    window.open(item.url, '_blank', 'noopener,noreferrer');
  };

  const atLimit = items.length >= maxCount;
  const labelOf = (item: AttachmentValue) => item.name || `附件${item.fileId}`;

  return (
    <div className="flex flex-col gap-2">
      <Upload
        accept={accept}
        // 上传列表由本组件自绘（showUploadList=false），Upload 保持无状态：
        // 它的内部列表只用于 maxCount 计数，与「删除后可再传」会打架，故不接管。
        fileList={[]}
        showUploadList={false}
        multiple
        beforeUpload={handleBeforeUpload}
        customRequest={handleUpload}
      >
        <Button
          icon={<UploadOutlined />}
          loading={uploading}
          disabled={atLimit}
          aria-label="上传附件"
        >
          {atLimit ? `最多 ${maxCount} 个` : '上传附件'}
        </Button>
      </Upload>

      {items.length > 0 && (
        <ul className="m-0 p-0 list-none flex flex-col gap-1">
          {items.map((item) => (
            <li
              key={item.fileId}
              className="flex items-center gap-2 text-sm px-2 py-1 rounded border border-[var(--ams-border)]"
            >
              <span className="flex-1 min-w-0 truncate" title={item.name}>
                {labelOf(item)}
              </span>
              <Button
                type="link"
                size="small"
                icon={<DownloadOutlined />}
                onClick={() => handleDownload(item)}
                aria-label={`下载 ${labelOf(item)}`}
              >
                下载
              </Button>
              <Button
                type="link"
                size="small"
                danger
                icon={<DeleteOutlined />}
                onClick={() => handleRemove(item.fileId)}
                aria-label={`删除 ${labelOf(item)}`}
              >
                删除
              </Button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

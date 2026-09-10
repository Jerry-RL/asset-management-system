import { Upload, message } from 'antd';
import { api } from '@/lib/api';

export interface UploadedFile {
  fileId: number;
  url: string;
}

/** 后端统一附件接口返回体 */
interface FileView {
  fileId: number;
  url: string;
  fileName?: string;
}

/**
 * 上传单个文件到统一附件接口（POST /files/upload）。
 * 走 api 封装以复用鉴权与错误处理，避免各页面各自拼 fetch。
 */
export const uploadFile = async (file: File, bizType: string): Promise<UploadedFile> => {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('bizType', bizType);
  const data = await api.upload<FileView>('/files/upload', formData);
  return { fileId: data.fileId, url: data.url };
};

/** 图片上传前置校验：类型与大小不合规时返回 Upload.LIST_IGNORE */
export const beforeUploadImage = (file: File, maxMB = 5) => {
  if (!file.type.startsWith('image/')) {
    message.error('请上传图片文件');
    return Upload.LIST_IGNORE;
  }
  if (file.size > maxMB * 1024 * 1024) {
    message.error(`图片大小不能超过 ${maxMB}MB`);
    return Upload.LIST_IGNORE;
  }
  return true;
};

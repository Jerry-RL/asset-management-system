import { Modal } from 'antd';

export type ConfirmDeleteOptions = {
  /** 展示名称，缺省时用通用文案 */
  name?: string;
  /** 资源类型，如「项目」「固定资产」 */
  resourceLabel?: string;
  onOk: () => void | Promise<void>;
};

/**
 * 删除二次确认弹框（全站统一入口，禁止无确认直接删）。
 */
export const confirmDelete = ({ name, resourceLabel, onOk }: ConfirmDeleteOptions) => {
  const subject = name?.trim()
    ? `「${name.trim()}」`
    : resourceLabel
      ? `该${resourceLabel}`
      : '该记录';

  return confirmDangerous({
    title: '确认删除',
    content: `确定删除${subject}吗？删除后不可恢复。`,
    okText: '确定删除',
    onOk,
  });
};

export type ConfirmDangerousOptions = {
  title: string;
  content: string;
  okText?: string;
  /** 默认 danger；通过类操作用 primary */
  okType?: 'danger' | 'primary' | 'default';
  onOk: () => void | Promise<void>;
};

/** 危险/关键操作二次确认弹框。 */
export const confirmDangerous = ({
  title,
  content,
  okText = '确定',
  okType = 'danger',
  onOk,
}: ConfirmDangerousOptions) => {
  Modal.confirm({
    title,
    content,
    okText,
    cancelText: '取消',
    okType,
    centered: true,
    autoFocusButton: 'cancel',
    onOk,
  });
};

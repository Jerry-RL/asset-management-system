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

/**
 * 二次确认并**返回用户选择**：确认 `true` / 取消 `false`。
 *
 * <p>与 {@link confirmDangerous} 的区别是「调用方要不要按结果分支」：
 * 后者把异步工作直接交给弹框的 `onOk`（弹框自己承担 loading 与失败处理），
 * 适合「点了就必须执行」的危险操作；本函数用于「用户点取消后要静默返回」的场景，
 * 例如「路由未注册仍要保存」——取消不该报错，只是不保存。
 */
export const confirmProceed = ({
  title,
  content,
  okText = '确定',
  okType = 'danger',
}: Omit<ConfirmDangerousOptions, 'onOk'>): Promise<boolean> =>
  new Promise((resolve) => {
    let settled = false;
    const done = (value: boolean) => {
      if (settled) return;
      settled = true;
      resolve(value);
    };
    Modal.confirm({
      title,
      content,
      okText,
      cancelText: '取消',
      okType,
      centered: true,
      onOk: () => done(true),
      onCancel: () => done(false),
    });
  });

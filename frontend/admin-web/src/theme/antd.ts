import type { ThemeConfig } from 'antd';

export const amsAntdTheme: ThemeConfig = {
  token: {
    colorPrimary: '#1677ff',
    borderRadius: 6,
    colorBgLayout: '#f5f6f8',
    fontFamily:
      "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif",
  },
  components: {
    Button: {
      borderRadius: 6,
    },
    Table: {
      headerBg: '#fafafa',
      rowHoverBg: '#f5f9ff',
    },
    Tag: {
      borderRadiusSM: 4,
    },
    Layout: {
      headerBg: '#ffffff',
      siderBg: '#ffffff',
      bodyBg: '#f5f6f8',
    },
  },
};

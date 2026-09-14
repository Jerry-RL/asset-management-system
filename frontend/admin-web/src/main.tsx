import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import App from './App';
import { amsAntdTheme } from './theme/antd';
import { logger } from './lib/log';
import './index.css';

// 全局错误钩子（设计 §6.1）：window.onerror + unhandledrejection 一次装好。
// 放在 render 之前 —— 首次渲染就崩的组件（最常见的一类）也必须在钩子覆盖范围内。
// 返回值是卸载函数，SPA 全程不会用到，故不保存。
logger.install();

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider locale={zhCN} theme={amsAntdTheme}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </ConfigProvider>
  </React.StrictMode>,
);

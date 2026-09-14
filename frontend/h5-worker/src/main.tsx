import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import { logger } from './lib/log';
import './index.css';

// 全局错误钩子（设计 §6.1）：window.onerror + unhandledrejection 一次装好。
// 放在 render 之前 —— 首次渲染就崩的组件（最常见的一类）也必须在钩子覆盖范围内。
logger.install();

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </React.StrictMode>,
);

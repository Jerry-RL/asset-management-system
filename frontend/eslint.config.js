import js from '@eslint/js';
import eslintConfigPrettier from 'eslint-config-prettier';
import reactHooks from 'eslint-plugin-react-hooks';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  {
    ignores: [
      '**/dist/',
      '**/build/',
      '**/node_modules/',
      '**/coverage/',
      '**/*.min.js',
      // 微信原生小程序由微信开发者工具管理，不走前端 TS/JS 工具链
      '**/miniprogram-*/**',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  eslintConfigPrettier,
  {
    rules: {
      '@typescript-eslint/no-unused-vars': ['warn', { argsIgnorePattern: '^_' }],
      '@typescript-eslint/no-explicit-any': 'warn',
    },
  },
  {
    // Node 环境下运行的构建脚本与纯逻辑单测（不是浏览器代码），需要 Node 全局变量。
    // 只授给 `.mjs`：各应用的 `.ts/.tsx` 都跑在浏览器里，一并放开会把
    // 「误用 Node API / 变量名拼错」这类真实缺陷变成静默通过。
    files: ['**/*.mjs'],
    languageOptions: {
      globals: { process: 'readonly', console: 'readonly', Buffer: 'readonly' },
    },
  },
  {
    // 仅启用 Hook 的核心两条规则：
    // 插件需先注册，代码中的 eslint-disable react-hooks/exhaustive-deps 才能被识别。
    // 未采用 configs['recommended-latest']（含 React Compiler 系 17 条规则），避免一次性引入大量错误。
    files: ['**/*.{js,jsx,ts,tsx}'],
    plugins: { 'react-hooks': reactHooks },
    rules: {
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'warn',
    },
  },
);

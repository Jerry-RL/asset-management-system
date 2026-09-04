# 代码规范与 Git 规范

## 1. 通用原则

- 可读性优先于炫技；与现有模块风格保持一致
- 业务规则写在领域层，Controller 只做参数校验与编排
- 禁止在 Controller 直接写 SQL
- 敏感信息不得写入日志

## 2. 命名规范

### 2.1 TypeScript / JavaScript

| 类型 | 规范 | 示例 |
|------|------|------|
| 变量/函数 | camelCase | `getAssetById` |
| 类/组件 | PascalCase | `AssetService`, `BillTable` |
| 常量 | UPPER_SNAKE | `MAX_PAGE_SIZE` |
| 文件（React） | PascalCase.tsx | `AssetDetail.tsx` |
| 文件（其他） | kebab-case 或 camelCase | `asset.service.ts` |
| 事件处理 | handle 前缀 | `handleSubmit` |
| API 路径 | kebab-case | `/lease-listings` |
| 数据库表/列 | snake_case | `lease_control_status` |

### 2.2 业务枚举

与 SRS 状态机字符串一致，集中定义：

```typescript
export const LeaseControlStatus = {
  Vacant: 'vacant',
  Leased: 'leased',
  // ...
} as const;
```

## 3. 注释

- 不写「是什么」的废话注释
- 必须注释：非显而易见的业务规则、状态机转换、金额计算、第三方回调验签
- 公共 API 使用 JSDoc

## 4. 前端（React + TypeScript）

- 使用函数组件 + Hooks
- 样式：Tailwind CSS；避免内联 style
- 类型：禁止 `any`（除非有 eslint-disable 说明）
- 列表页：统一 `useTableQuery` 封装分页筛选
- API 类型从 `packages/api-types`（OpenAPI 生成）引用

## 5. 后端（Java + Spring Boot）

- 分层：`controller → service → mapper`（DTO/Entity 分离）
- 数据访问：**MyBatis-Plus**（DDL 由 Flyway 管理，见 ADR-0017）
- 统一 `ApiResponse` + 业务异常 + 错误码（见 api/README.md）
- 写操作：`@Transactional` + Redis 幂等（Idempotency-Key）
- 输入校验：Jakarta Validation（`@Valid`），与 OpenAPI schema 对齐
- 日志：MDC `traceId`（TraceIdFilter）
- 包名：`com.ams.modules.{domain}`、`com.ams.platform.{capability}`
- 禁止跨模块直接注入其他模块的 Mapper

## 6. 数据库

- 迁移脚本命名：`V{version}__{description}.sql`
- 禁止生产手工 DDL
- 索引变更须附说明与回滚评估

## 7. Git 分支策略

| 分支 | 用途 |
|------|------|
| `main` | 生产就绪，受保护 |
| `develop` | 集成分支 |
| `feature/*` | 功能开发 |
| `fix/*` | 缺陷修复 |
| `release/*` | 发布准备 |

流程：`feature` → PR → `develop` → 测试通过后 → `release` → `main` + tag。

## 8. Commit Message（Conventional Commits）

```
<type>(<scope>): <subject>

[optional body]

[optional footer]
```

**type**

| type | 说明 |
|------|------|
| feat | 新功能 |
| fix | 修复 |
| docs | 文档 |
| style | 格式（不影响逻辑） |
| refactor | 重构 |
| test | 测试 |
| chore | 构建/工具 |

**示例**

```
feat(contract): auto-generate payment plan on approval

Refs FR-PRICE-005
```

```
fix(billing): prevent duplicate payment with idempotency key
```

## 9. Pull Request

- 一个 PR 聚焦一个主题
- 必须说明：变更说明、关联需求 ID、测试情况
- API 变更必须同时更新 `docs/api/openapi.yaml`
- 库表变更必须同时更新 Flyway + `docs/database/数据库设计.md`

## 10. 工具配置

| 工具 | 用途 | 路径 |
|------|------|------|
| Maven | Java 构建与测试 | `backend/pom.xml` |
| ESLint + Prettier | TS/JS 格式与规则 | `eslint.config.js`、`.prettierrc` |
| EditorConfig | 编辑器统一缩进 | `.editorconfig` |
| Husky + lint-staged | 提交前 lint（backend 脚手架后启用） | 见 setup-pre-commit |
| commitlint | Commit 格式校验（可选） | — |

根目录命令（需 `pnpm install`）：

```bash
pnpm lint          # ESLint
pnpm format        # Prettier
pnpm api:lint      # OpenAPI 校验
```

## 11. Code Review 检查清单

- [ ] 是否符合状态机，有无绕过业务单据改状态
- [ ] 金额计算与精度
- [ ] 权限与数据范围
- [ ] 幂等与事务
- [ ] 日志与 traceId
- [ ] 文档/迁移是否同步

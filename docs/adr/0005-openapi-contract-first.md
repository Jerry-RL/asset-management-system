# ADR-0005: OpenAPI 契约优先的 API 设计

- **状态**：Accepted
- **日期**：2026-08-26

## 上下文

三端 + 第三方（支付、发票、电子签、ERP）需要稳定接口契约。

## 决策

- 维护 `docs/api/openapi.yaml` 为 API 单一事实来源
- 开发流程：先更新 OpenAPI → Review → 实现 → 契约测试
- 使用 Swagger UI / Redoc 从 OpenAPI 生成可读文档
- 错误响应统一结构（见 api/README.md）

## 后果

- 正面：前后端并行、Mock 联调、第三方对接清晰
- 负面：需约束「先改文档再改代码」

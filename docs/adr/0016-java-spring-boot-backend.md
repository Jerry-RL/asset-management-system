# ADR-0016: 采用 Java Spring Boot 作为后端主栈

- **状态**：Accepted
- **日期**：2026-08-27
- **决策者**：架构组、技术负责人
- **替代**：ADR-0002 中 Node.js + Express 表述（架构风格仍为模块化单体）

## 上下文

[后端技术栈横向对比.md](../design/后端技术栈横向对比.md) 评估后，业主与团队确定采用 **Java (Spring Boot)**，以更好对接国资运维体系、ERP/业财集成及长期维护。SRS §6 允许 Java 作为后端语言。

原仓库文档与 ADR 收敛于 Node.js + Express + Knex + BullMQ，需整体切换为 Spring 生态。

## 决策

1. **运行时**：JDK **21 LTS**
2. **框架**：**Spring Boot 3.3.x**（Web、Security、Validation、Actuator）
3. **架构**：保持 **模块化单体**（ADR-0002 精神不变），按领域分包 `com.ams.modules.*`
4. **构建**：**Maven**（`backend/pom.xml`）
5. **API 契约**：OpenAPI 3.0 仍为单一事实来源（ADR-0005）；**springdoc-openapi** 暴露文档与校验
6. **前端**：仍为 React + 微信原生小程序；类型由 OpenAPI 生成至 `admin-web`（openapi-typescript 等）
7. **仓库结构**：**混合 Monorepo** — 前端 `pnpm workspaces` + 后端独立 Maven 模块（更新 ADR-0012）

## 备选方案

| 方案 | 结论 |
|------|------|
| 维持 Node.js | 交付快但与业主 Java 栈不一致 → 不采纳 |
| Go 主 API | CRUD 单体工作量大 → 不采纳 |
| Kotlin + Spring | 可选；一期统一 Java 降低交接成本 |

## 后果

- 正面：Spring 生态、事务、Security、国资 Java 人才、ERP 集成
- 负面：失去前后端 TS 类型同仓；镜像与内存大于 Node；Sprint 0 底座 +1–2 周
- 关联 ADR：[0017 MyBatis+Flyway](./0017-mybatis-plus-flyway.md)、[0018 JobRunr](./0018-jobrunr-async-jobs.md)

## 关联

- [技术选型说明书.md](../design/技术选型说明书.md) V2.0
- DSD V1.5

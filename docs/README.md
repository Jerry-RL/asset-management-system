# 项目文档索引

按 **立项 → 需求 → 设计 → 开发 → 测试 → 运维 → 交接** 全生命周期组织。

---

## 立项

| 文档 | 路径 | 受众 |
|------|------|------|
| 项目章程 | [initiation/项目章程.md](./initiation/项目章程.md) | 发起人、PM |
| 商业论证 | [initiation/商业论证.md](./initiation/商业论证.md) | 发起人、财务 |

---

## 需求

| 文档 | 路径 | 受众 |
|------|------|------|
| 需求规格说明书（SRS）V2.0 | [需求规格说明书.md](./需求规格说明书.md) | 全体 |
| 用户故事地图 | [requirements/用户故事地图.md](./requirements/用户故事地图.md) | 产品、开发、测试 |
| 业务完善方案（分析底稿） | [业务完善方案补充.md](./业务完善方案补充.md) | 产品、架构 |

---

## 设计

| 文档 | 路径 | 受众 |
|------|------|------|
| 概要设计说明书（HLD） | [design/概要设计说明书.md](./design/概要设计说明书.md) | 架构、开发 |
| 详细设计说明书（DSD） | [详细设计说明书.md](./详细设计说明书.md) | 开发、测试 |
| 接口规范 | [api/README.md](./api/README.md) · [openapi.yaml](./api/openapi.yaml) | 前后端、第三方 |

---

## 开发

| 文档 | 路径 | 受众 |
|------|------|------|
| 数据库设计文档 | [database/数据库设计.md](./database/数据库设计.md) | 开发、DBA |
| 架构决策记录（ADR） | [adr/README.md](./adr/README.md) | 架构、技术负责人 |
| 代码规范与 Git 规范 | [代码规范与Git规范.md](./代码规范与Git规范.md) | 全体开发者 |
| 变更日志 | [../CHANGELOG.md](../CHANGELOG.md) | 全体 |

---

## 测试

| 文档 | 路径 | 受众 |
|------|------|------|
| 测试计划 | [testing/测试计划.md](./testing/测试计划.md) | QA、PM |
| 测试用例 | [testing/测试用例.md](./testing/测试用例.md) | QA、开发 |
| 性能测试报告 | [testing/性能测试报告.md](./testing/性能测试报告.md) | QA、架构、运维 |

---

## 运维

| 文档 | 路径 | 受众 |
|------|------|------|
| 部署手册 | [deployment/部署手册.md](./deployment/部署手册.md) | 开发、运维 |
| 应急预案 | [deployment/应急预案.md](./deployment/应急预案.md) | 运维、值班 |
| 部署与运维手册（FAQ 合集） | [deployment/部署与运维手册.md](./deployment/部署与运维手册.md) | 运维 |

---

## 交接

| 文档 | 路径 | 受众 |
|------|------|------|
| 用户手册 | [user/用户手册.md](./user/用户手册.md) | 运营、租户支持 |
| 系统交接文档 | [handover/系统交接文档.md](./handover/系统交接文档.md) | 建设方、业主 |

---

## 其他

| 资料 | 路径 |
|------|------|
| 设计稿原始材料 | [source-material/](./source-material/) |

---

## 维护原则

1. **文档即代码**：Markdown + Git，避免 Word 版本漂移。
2. **单一事实来源**：API → `openapi.yaml`；库表 → Flyway + `database/数据库设计.md`。
3. **变更联动**：需求变 SRS → 故事地图 → 设计 → 接口/库表 → 测试用例。
4. **最小必要**：每份文档明确受众；自动化：OpenAPI、SchemaSpy（可选）、Mermaid 图。

## 文档关系图

```mermaid
flowchart LR
  charter[项目章程] --> srs[SRS]
  business[商业论证] --> srs
  srs --> story[用户故事地图]
  srs --> hld[概要设计]
  hld --> dsd[详细设计]
  dsd --> api[接口规范]
  dsd --> db[数据库设计]
  dsd --> adr[ADR]
  srs --> testplan[测试计划]
  testplan --> testcase[测试用例]
  testplan --> perf[性能测试报告]
  dsd --> deploy[部署手册]
  deploy --> emergency[应急预案]
  srs --> userdoc[用户手册]
  deploy --> handover[系统交接文档]
```

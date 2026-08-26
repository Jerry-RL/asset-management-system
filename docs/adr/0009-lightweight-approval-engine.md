# ADR-0009: 轻量自研审批引擎

- **状态**：Accepted
- **日期**：2026-08-26

## 上下文

SRS 要求合同、招租、处置、减免、退款等多类审批，且需与任务中心、三重一大（FR-COMP-*）联动。可选 Camunda/Activiti 等 BPM 引擎，但引入复杂度高、与 Node 栈集成成本大。

## 决策

采用 **轻量自研审批引擎**：

- 表：`approval_flow_def`（流程定义 JSON）、`approval_instance`、`approval_task`
- 节点类型：串行、会签、条件分支（金额/类型）
- 与业务单据 1:1：`biz_type` + `biz_id`
- 审批完成发布领域事件 `ApprovalCompleted` → 触发 PricingEngine、租控、通知等

流程配置在 PC「流程设置」（FR-CON-005）维护，存 JSON，不做 BPMN 可视化编辑器（二期可选）。

## 备选方案

| 方案 | 结论 |
|------|------|
| Camunda | 功能强，运维与学习成本高 → 暂缓 |
| 硬编码 if/else | 不可配置 → 拒绝 |
| 轻量 JSON 状态机 | 可配置、可测试 → **采纳** |

## 后果

- 正面：与 Express 单体无缝、事务一致、任务中心易集成
- 负面：复杂并行网关需二期扩展；需自测覆盖各 `biz_type`

## 关联

- DSD §4.7、§4.8
- SRS：FR-CON-005、FR-COMP-*

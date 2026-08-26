# ADR-0014: 微信小程序原生开发（一期）

- **状态**：Accepted
- **日期**：2026-08-26

## 上下文

需交付用户端、工作端两个小程序。DSD 写「微信原生 / Taro 可选」，未定论。

## 决策

**一期采用微信原生**（WXML + TS），分两个独立小程序目录：

- `miniprogram-tenant`：承租方缴费、报修、招租
- `miniprogram-worker`：外勤巡检、清场、催缴张贴

共享逻辑抽取至 `packages/shared`（常量、格式化）；API 类型引用 `packages/api-types`。

**Taro/React 复用** 作为 P2 备选，当双端 UI 复用需求明确时再评估迁移成本。

## 后果

- 正面：微信官方能力（支付、订阅消息）集成最直接
- 负面：双端 UI 无法与 React 共享组件

## 关联

- [技术选型说明书.md](../design/技术选型说明书.md)

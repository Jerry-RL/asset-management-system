# 接口规范说明

> OpenAPI 定义文件：[openapi.yaml](./openapi.yaml)  
> 本地预览：`npx @redocly/cli preview-docs docs/api/openapi.yaml` 或导入 Swagger UI

## 1. 基本信息

| 项 | 值 |
|----|-----|
| 风格 | RESTful JSON API |
| 基础路径 | `/api/v1` |
| 协议 | HTTPS（生产强制） |
| 字符编码 | UTF-8 |
| 时间格式 | ISO 8601，`2026-08-26T12:00:00+08:00` |
| 金额 | 单位「分」或「元」带 `decimal(18,2)`，接口统一用 **元，两位小数**（字段名后缀 `Amount`） |

## 2. 鉴权

### 2.1 PC 管理后台

```http
POST /api/v1/auth/login
Content-Type: application/json
X-Client-Type: admin

{
  "username": "admin",
  "password": "***",
  "captchaId": "uuid",
  "captchaCode": "Z6VTC"
}
```

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "accessToken": "eyJ...",
    "refreshToken": "eyJ...",
    "expiresIn": 7200,
    "user": { "id": 1, "name": "办事员", "roles": ["clerk"] }
  }
}
```

后续请求：

```http
Authorization: Bearer <accessToken>
X-Client-Type: admin
```

### 2.2 小程序（用户端 / 工作端）

```http
POST /api/v1/auth/wechat/login
X-Client-Type: tenant-mp   # 或 worker-mp

{ "code": "微信 wx.login code" }
```

绑定身份后同样返回 JWT。

### 2.3 Token 刷新

```http
POST /api/v1/auth/refresh
{ "refreshToken": "eyJ..." }
```

## 3. 统一响应结构

### 3.1 成功

```json
{
  "code": 0,
  "message": "ok",
  "data": { },
  "traceId": "abc123"
}
```

### 3.2 分页列表

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "list": [],
    "total": 841,
    "page": 1,
    "pageSize": 10
  },
  "traceId": "abc123"
}
```

### 3.3 错误

```json
{
  "code": 40001,
  "message": "合同状态不允许此操作",
  "data": null,
  "traceId": "abc123"
}
```

## 4. 错误码枚举

| code | 含义 | 处理建议 |
|------|------|----------|
| 0 | 成功 | — |
| 40000 | 请求参数错误 | 检查字段校验信息 |
| 40001 | 业务规则校验失败 | 展示 `message` |
| 40100 | 未登录或 Token 失效 | 跳转登录 |
| 40101 | Token 已过期 | 调用 refresh |
| 40300 | 无权限 | 提示联系管理员 |
| 40301 | 数据权限不足 | 不可访问该公司/项目数据 |
| 40400 | 资源不存在 | 检查 ID |
| 40900 | 状态冲突（乐观锁/状态机） | 刷新后重试 |
| 40901 | 重复提交（幂等拦截） | 忽略或查询结果 |
| 42201 | 签约价低于底价 | 走超低价审批 |
| 42202 | 租户在黑名单 | 禁止签约 |
| 42900 | 请求过于频繁 | 稍后重试 |
| 50000 | 服务器内部错误 | 联系技术支持，提供 traceId |
| 50001 | 第三方服务异常 | 支付/短信/发票等，可重试 |
| 50300 | 服务维护中 | 稍后重试 |

业务子域可扩展：`41xxx` 资产、`42xxx` 合同、`43xxx` 收费、`44xxx` 维修。

## 5. 通用约定

### 5.1 查询参数

| 参数 | 说明 |
|------|------|
| `page` | 页码，从 1 开始，默认 1 |
| `pageSize` | 每页条数，默认 10，最大 100 |
| `sort` | 排序，如 `createdAt:desc` |
| `companyId` | 数据范围（有权限时） |

### 5.2 幂等

写操作支持请求头：

```http
Idempotency-Key: <uuid>
```

用于：创建收款、发起支付、审批提交等。

### 5.3 文件上传

```http
POST /api/v1/files/upload
Content-Type: multipart/form-data
```

返回 `fileId`，业务单据保存 `fileId` 引用。

## 6. 模块与路径索引

| 模块 | 前缀 | 说明 |
|------|------|------|
| 认证 | `/auth` | 登录、刷新、登出 |
| 组织 | `/org` | 公司、部门、用户 |
| 项目资产 | `/projects`, `/assets` | 项目、台账、租控 |
| 招租 | `/lease-listings`, `/tender` | 招租、公开遴选 |
| 合同 | `/contracts` | 签约、变更、退租 |
| 计费 | `/billing` | 计划、账单、收款 |
| 发票 | `/invoices` | 开票、核销 |
| 催缴 | `/dunning` | 欠费、催缴等级 |
| 维修 | `/repairs`, `/inspections` | 报修、巡查 |
| 任务 | `/tasks` | 任务中心 |
| 预警 | `/alerts` | 配置、记录 |
| 看板 | `/dashboard` | 经营指标 |
| 盘点盘活 | `/audits`, `/revitalization` | 经营性盘点、空置盘活 |
| 业财 | `/finance` | 对账、月结 |
| 系统 | `/system` | 菜单、角色、日志 |

完整路径、Schema 见 **openapi.yaml**。

## 7. 与代码同步

1. 接口变更先改 `openapi.yaml`，PR 中 @ 前后端 Review。
2. CI 运行 OpenAPI 校验（`redocly lint`）。
3. 可选：从 OpenAPI 生成 TypeScript 类型到 `packages/api-types`。

## 8. 第三方回调

| 回调 | 路径 | 说明 |
|------|------|------|
| 微信支付 | `POST /api/v1/callbacks/wechat-pay` | 验签后更新订单 |
| 电子发票 | `POST /api/v1/callbacks/invoice` | 开票结果 |
| 电子签 | `POST /api/v1/callbacks/esign` | 签署完成 |

回调接口使用签名鉴权，不走 JWT。

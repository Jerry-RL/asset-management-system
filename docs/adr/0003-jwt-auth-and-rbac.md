# ADR-0003: JWT 鉴权与 RBAC 数据权限

- **状态**：Accepted
- **日期**：2026-08-26

## 上下文

系统多角色、多公司、多项目；PC、用户端小程序、工作端小程序共用 API。

## 决策

- **鉴权**：JWT（Access Token + Refresh Token）；Header：`Authorization: Bearer <token>`
- **端标识**：`X-Client-Type: admin | tenant-mp | worker-mp`
- **权限**：RBAC（角色–菜单–按钮）+ 数据范围（公司 / 部门 / 项目 ID 列表注入查询条件）
- **小程序**：微信 `code` 换 `session`，绑定系统用户或租户账号后签发 JWT

## 后果

- 正面：无状态、易水平扩展、三端统一
- 负面：Token 吊销需黑名单或短过期 + Refresh 策略

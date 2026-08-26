# 数据库文档工具

| 文档 | 路径 | 工具 |
|------|------|------|
| 数据库设计（主文档） | [数据库设计.md](./数据库设计.md) | Markdown + Mermaid ER |
| DBML（dbdocs.io） | [ams.dbml](./ams.dbml) | [dbdocs](https://dbdocs.io/) |
| SchemaSpy（ER 图 HTML） | [schemadoc/](./schemadoc/) | SchemaSpy + Docker |

## dbdocs.io

1. 安装 CLI：`npm i -g dbdocs`
2. 登录：`dbdocs login`
3. 发布：`dbdocs build docs/database/ams.dbml --project ams`
4. 在线链接可在 CI 或 README 中引用

## SchemaSpy（需已有 PostgreSQL + Flyway 迁移）

```bash
# 1. 启动本地 PG（见 ../../docker/docker-compose.yml）
cd docker && docker compose up -d postgres

# 2. 执行迁移（backend 就绪后）
# cd backend && pnpm run migrate

# 3. 生成 HTML 文档
cd docs/database/schemadoc
docker compose run --rm schemadoc
# 输出：docs/database/schemadoc/output/
```

`output/` 已加入 `.gitignore`，由 CI 或本地按需生成。

## 维护规则

1. 表结构变更 → 更新 Flyway `V*` 脚本 + `数据库设计.md` + `ams.dbml`
2. 发布前运行 SchemaSpy 对比 ER 与文档是否一致

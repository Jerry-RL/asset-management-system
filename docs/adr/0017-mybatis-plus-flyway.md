# ADR-0017: MyBatis-Plus 数据访问 + Flyway 管理 Schema

- **状态**：Accepted
- **日期**：2026-08-27
- **替代**：ADR-0010（Knex + Flyway）在 Java 后端下 **Superseded**

## 上下文

ADR-0006 规定 Flyway 为 DDL 单一来源。原 Node 方案用 Knex 仅作查询。切换 Spring Boot 后需选定 Java 侧数据访问层。

## 决策

1. **Flyway**：`backend/src/main/resources/db/migration/V*.sql`（与 `docs/database/` 设计文档同步）
2. **ORM/访问**：**MyBatis-Plus 3.5.x**
   - Entity + Mapper 接口
   - 复杂报表：XML Mapper 或 `@Select` 参数绑定
   - 禁止字符串拼接 SQL
3. **事务**：`@Transactional` 于 Service 层
4. **连接池**：HikariCP（Spring Boot 默认）
5. **禁止**：JPA/Hibernate 反向生成 DDL 与 Flyway 双源

## 备选

| 方案 | 结论 |
|------|------|
| JPA + Flyway | 双范式、复杂 SQL 弱 → 不采纳 |
| 纯 JDBC | 样板代码多 → 不采纳 |
| MyBatis-Plus + Flyway | 职责清晰、国资项目常见 → **采纳** |

## 后果

- Repository 层命名为 `*Mapper`，Service 注入 Mapper
- 字段加密（ADR-0015）在 TypeHandler 或 Service 层实现

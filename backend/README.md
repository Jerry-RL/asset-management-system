# Backend — Spring Boot API

Java **21** + **Spring Boot 3.3** + MyBatis-Plus + Flyway + JobRunr。

## 前置

- JDK 21
- Maven 3.9+
- Docker（本地 PG / Redis / MinIO）：`docker compose -f docker/docker-compose.yml up -d`

## 开发

```bash
cd backend
mvn spring-boot:run
```

- API：http://localhost:8080
- Swagger UI：http://localhost:8080/swagger-ui.html
- 健康检查：http://localhost:8080/api/v1/health/ready
- JobRunr Dashboard（dev）：http://localhost:8000

## 测试

```bash
mvn test
```

## 构建

```bash
mvn -DskipTests package
java -jar target/ams-backend-0.1.0-SNAPSHOT.jar
```

## 包结构

```
com.ams
├── config/           # Security、Filter、MyBatis
├── common/           # ApiResponse、异常、工具
├── platform/         # auth、approval、notification、job、event
└── modules/          # asset、contract、billing…
```

详见 [详细设计说明书](../docs/详细设计说明书.md) §3.4、[ADR-0016](../docs/adr/0016-java-spring-boot-backend.md)。

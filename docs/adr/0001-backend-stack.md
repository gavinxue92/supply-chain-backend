# ADR-0001：后端技术栈与关系数据库

- **状态**：已接受
- **日期**：2026-11-16

## 背景

业务服务从零开始，需要支撑微信小程序公开浏览接口和运营管理后台 API。产品、规格、分类、账号和审计记录之间存在明确关系，需要可追踪的数据结构和演进方式。用户指定 Java 与 MySQL。

## 决策

- 使用 Java 21 和 Spring Boot 构建后端服务。
- 使用 Maven 管理构建与依赖。
- 使用 MySQL 8 保存业务和管理数据。
- 数据库结构通过 `scripts/schema.sql` 直接部署；应用启动不执行数据库迁移。结构变更时维护完整的建表 SQL，并由部署人员按需应用。
- 使用 MyBatis-Plus 作为唯一持久层框架；Mapper 继承 `BaseMapper<T>`，常规条件查询使用类型安全的 `LambdaQueryWrapper`。禁止在业务代码中新增原生 `JdbcTemplate` 或手工拼接 SQL。
- 采用 `controller`、`service` / `service.impl`、`mapper`、`entity`、`dto`、`vo` 标准包结构。Controller 仅负责 HTTP 路由、`@Valid` 参数校验、调用 Service 和返回 `Result<T>`；业务规则、事务、密码校验和限流调度归 Service 层。
- 数据库实体、请求 DTO 和响应 VO 严格分离；禁止 API 直接返回 `Map<String, Object>` 或数据库实体。`Result<T>` 与 `@RestControllerAdvice` 全局异常处理器归入 `common` 包，认证过滤器归入 `security` 包。

## 后果

- 团队可采用成熟的 Java 服务开发方式，MySQL 关系模型适合表达产品、规格、分类和账号关系。
- 新环境通过部署 SQL 脚本创建数据库结构；生产环境已有数据的结构调整由部署人员使用明确的 SQL 变更操作完成。
- 本地与部署环境需提供兼容的 Java、MySQL 配置。
- 常规实体增删改查和条件查询通过 MyBatis-Plus Mapper 执行；复杂查询应使用可维护的参数化 Mapper SQL，不得在 Controller 中绕过 Service 访问数据库。
- 现有直接使用 `JdbcTemplate` 的实现应在后续垂直切片中迁移到该分层结构；本次文档与依赖变更不代表业务 Java 代码已完成迁移。
- 缓存和其他基础设施未在本决策中指定，后续不得仅因技术栈已选而假定必须引入。

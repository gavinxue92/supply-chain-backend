## 架构宪法

- 后端采用 `controller`、`service` / `service.impl`、`mapper`、`entity`、`dto`、`vo` 的标准包结构和四层职责边界。
- `controller` 仅处理 HTTP 路由、`@Valid` 参数校验、调用 Service 和返回 `Result<T>`；禁止在 Controller 中编写业务逻辑、数据库访问或安全计算。
- 核心业务规则、事务、密码校验和限流调度由 Service 层负责。
- 持久层统一使用 MyBatis-Plus；Mapper 继承 `BaseMapper<T>`，查询优先使用 `LambdaQueryWrapper` 和参数化 Mapper 接口。禁止新增原生 `JdbcTemplate` 和手工拼接 SQL。
- `entity`、`dto`、`vo` 分别表示数据库实体、请求 DTO 和响应 VO；API 不得直接返回 `Map<String, Object>` 或数据库实体。
- `Result<T>`、全局异常处理器（`@RestControllerAdvice`）放在 `common` 包；安全认证过滤器放在 `security` 包。
- 新增或修改代码必须遵循以上边界；重构已有代码时按垂直切片逐步迁移，不得在新功能中继续扩大违反规范的实现。
- 新增代码使用的第三方 API 必须在 `pom.xml` 中声明直接依赖，不得依赖偶然的传递依赖；按运行/测试用途设置 scope，并在每个实现任务中同步清理确认无用的直接依赖。新增/调整依赖后验证 Maven 解析和编译。
- 架构重构按模块分片实施；每片完成后必须通过 Maven 编译，并运行该片受影响的现有接口测试。测试只验证对应 HTTP/API 契约及接口功能，不重复测试无关功能，也不以单元测试替代接口验证。
- 接口测试应通过 HTTP 调用应用并检查状态码、响应体和必要的可观察结果；不得将 Controller、Service 或 Mapper 的内部实现作为本片验收面。只在对应接口行为发生变化时调整其测试，不扩大到无关测试范围。
- 执行分片顺序、当前架构违规清单和逐片验证范围见 `docs/architecture-refactoring-plan.md`。

## Agent 技能

### 问题跟踪

问题和需求使用 `.scratch/` 下的本地 Markdown 文件跟踪。详见 `docs/agents/issue-tracker.md`。

### 领域文档

采用单上下文布局。详见 `docs/agents/domain.md`。

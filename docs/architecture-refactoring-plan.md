# 后端架构扫描与渐进式重构计划

扫描范围：`src/main/java` 下全部 10 个 Java 类。下方违规清单是本轮重构前的扫描基线；请以“当前状态”了解最新结果。

## 初次扫描违规清单（重构前）

| 文件 | 发现 | 影响 |
|---|---|---|
| `src/main/java/dev/mattest/supplychain/auth/AdminAuthController.java` | 胖 Controller（第 30–181 行）；直接注入并使用 `JdbcTemplate`（第 36、45 行）；登录接口限流、密码校验、会话创建和 Cookie 处理等业务/安全流程位于 Controller；`csrf` 和 `login` 对外返回裸 `Map`（第 59、64、139 行）；请求/响应 record 嵌套在 Controller 中 | 违反 Controller 职责、MyBatis-Plus 持久层、DTO/VO 分离和统一 `Result<T>` 约束 |
| `src/main/java/dev/mattest/supplychain/auth/AdminAccountController.java` | 胖 Controller（第 27–149 行）；直接注入并使用 `JdbcTemplate`（第 30、35 行）；角色/密码规则、事务、账号变更及审计写入在 Controller 中；请求/响应 record 嵌套在 Controller 中 | 违反 Controller 职责、MyBatis-Plus 持久层和 DTO/VO 分离。第 149 行的 `Map<String, Object>` 是内部审计摘要参数，不是 API 返回值，不按“裸 Map 响应”计 |
| `src/main/java/dev/mattest/supplychain/auth/AdminSessionFilter.java` | 直接使用 `JdbcTemplate` 查询和更新会话（第 8、27、29 行）；认证过滤器位于 `auth` 包而非 `security` 包 | 认证过滤器绕过 Mapper/Service，且不符合公共安全组件包结构 |
| `src/main/java/dev/mattest/supplychain/auth/AdminBootstrap.java` | 直接使用 `JdbcTemplate` 读写管理员和审计数据（第 6、13、19 行） | 初始化流程绕过 MyBatis-Plus Mapper 与 Service |
| `src/main/java/dev/mattest/supplychain/api/HealthController.java` | 对外接口返回裸 `Map<String, String>`（第 13–14 行） | 违反统一 `Result<T>` 响应约束 |
| `src/main/java/dev/mattest/supplychain/config/SecurityConfiguration.java` | 安全配置位于 `config` 包 | 后续整理包结构时应并入 `security`，避免安全组件分散 |

扫描中未发现当前目录下的分类、产品、Mapper、Entity 或独立 DTO/VO 实现；分类、产品和媒体等业务模块属于后续新增切片。`LoginRateLimiter` 与 `PasswordPolicy` 本身未直接访问数据库，但应随认证切片归入 Service/安全职责边界。

## 当前状态

- 认证与管理员账号实现已迁移到 `controller`、`service` / `service.impl`、`mapper`、`entity`、`dto`、`vo`、`security` 包；相关 Controller 不再直接访问数据库、实现业务规则或返回裸 Map。
- 认证、会话和账号模块的 `JdbcTemplate` 已移除；会话过滤器和安全配置已迁至 `security`，使用 MyBatis-Plus Mapper 持久化。
- 应用启动不再要求或自动创建首个管理员；本地开发使用 `scripts/init-local-admin.sql` 手动插入仅限本地的临时账号，认证接口测试使用独立 SQL 测试夹具。
- 无状态 Cookie 会话认证不再在每个请求重新认证时清除 CSRF Token；登录限流、失败计数与锁定状态按接口行为进行验证。
- Maven package 和测试编译通过。认证、账号及健康接口已通过本地 HTTP 端到端验证（MySQL 26.10）；Docker Engine 不可用，Testcontainers 中基于目标 MySQL 8 的自动化集成测试尚不能运行。
- 健康检查已迁入标准 `controller` / `vo` 响应结构并返回 `Result<T>`；目前生产 Java 代码中未发现 `JdbcTemplate` 或裸 `Map` API 响应。
- 分类管理和产品草稿管理已新增标准 Controller/Service/Mapper/Entity/DTO/VO 分层，并为关键变更写入审计；分类可软删除后复用同级名称。产品上架/下架、归档/恢复和公开目录接口及 HTTP 集成测试代码已实现；Maven package 通过，Testcontainers HTTP 验收受本机 Docker Engine API 返回空 Engine 元数据阻断。
- 数据库不再由应用自动迁移；新环境先执行 `scripts/create-database.sql`，再执行 `scripts/schema.sql`。Docker Compose 仅在 MySQL 数据卷首次初始化时自动执行建表脚本；已有数据卷不会自动套用脚本变更。Testcontainers 测试直接执行建表 SQL。
- 分类/草稿及媒体/产品完整内容 Maven package 通过；相关 HTTP 行为已在隔离的本地 MySQL、MinIO 环境验证。媒体 Testcontainers 测试因本机 Docker pipe 返回空 Engine 信息而未能执行。
- 产品搜索/排序、分类树筛选及全局/产品客服策略 API 已实现并补充 HTTP 集成测试代码；Maven package 通过，Testcontainers HTTP 验收仍受本机 Docker Engine API 返回空 Engine 元数据阻断。
- 存量迭代 06（`.scratch/supply-chain-backend/issues/06-account-product-hard-delete-and-media.md`）按 ADR-0010/0011/0012 扩展：账号昵称与硬删除、客服联系人与产品挂载、媒体删除引用计数、产品归档后硬删除。文档与 `scripts/schema.sql` 先行全量更新；每片以 Maven 编译和手动 HTTP 验收为完成条件，不修复 Docker 阻断。

## 分片重构顺序

每片都先保持现有端点路径和既有业务行为，再将实现迁移到目标分层。若统一响应包装改变公开 JSON 结构，应在该片接口契约测试中显式确认新结构，避免无意的行为变化。完成一片后再进入下一片。

| 切片 | 模块和范围 | 主要改造 | 该片完成条件 | 状态 |
|---|---|---|---|---|
| 1 | 公共 API 基础与健康检查 | 建立 `common` 下的 `Result<T>` 和 `@RestControllerAdvice`；将健康接口迁入标准 `controller` 结构并返回统一结果 | 健康端点的 HTTP 状态码和响应契约通过接口测试；应用上下文启动且 Maven 编译通过 | 完成；HTTP 验证通过 |
| 2 | 安全认证、会话与初始化 | 将会话过滤器迁至 `security`；创建认证 Service、Mapper、Entity、DTO/VO；迁移登录、登出、改密、限流、密码校验；通过本地 SQL 脚本初始化临时管理员；删除该模块 `JdbcTemplate` | 认证相关 HTTP 端点的成功、未授权、限流/验证及会话行为接口测试通过；编译通过；认证相关现有接口测试通过 | 完成；HTTP 验证通过 |
| 3 | 管理员账号与审计 | 将账号 Controller 精简为路由/校验/Service 调用；通过 Mapper 实现账号和审计读写；将内嵌 record 移至 DTO/VO；使用统一响应和异常处理 | 账号列表、创建、停用、重置密码和权限错误等受影响 HTTP 契约测试通过；编译通过；该模块现有接口测试通过 | 完成；HTTP 验证通过 |
| 4 | 分类目录 | 新增分类 Entity、Mapper、DTO/VO、Service 和 Controller；落实层级、启停、排序、唯一性和删除限制 | 分类公开/管理端接口契约覆盖成功与关键拒绝场景；编译通过；该模块接口测试通过 | 管理端及公开接口已实现；Maven package 通过；Testcontainers 验收受本机 Docker Engine 阻断 |
| 5 | 产品目录与规格报价 | 新增产品、规格、关键词等实体及分层；实现草稿/上架/下架/归档、分类归属和报价接口 | 产品列表/搜索/详情及管理端产品接口契约通过；编译通过；该模块接口测试通过 | 草稿、生命周期、公开分类/产品列表与详情、搜索排序及客服展示已实现；Maven package 通过，HTTP 集成测试代码已补充；测试运行受本机 Docker Engine API 返回空 Engine 元数据阻断 |
| 6 | 图片资源与客服配置 | 按相同分层新增媒体资源、全局客服及产品客服接口；落实上传授权/确认、配置继承和关闭策略 | 图片和客服配置相关 HTTP 契约及关键失败响应通过接口测试；编译通过；该模块接口测试通过 | 媒体资源上传/确认、公开读取及产品图片关联已实现并通过本地 HTTP 验收；自动化容器测试受本机 Docker pipe 配置阻断；客服配置待后续 |

## 每片验证规则

- 必须运行 Maven 编译，并运行本片涉及的现有接口测试；测试失败时先修复本片引入的问题，不推进下一片。
- 测试通过 HTTP/API 边界验证接口可执行、状态码和响应契约正确，以及必要的可观察结果；不为本片重复验证无关功能，不要求新增内部类单元测试。
- 仅更新本片接口行为实际受影响的测试。数据库建表脚本验证、对象存储链路等与本片无关的测试不重复执行；当本片确实修改相应能力时再验证。
- 现有认证集成测试通过随机 HTTP 端口调用认证 API，可作为切片 2/3 的接口验证基础；当前测试中的数据库夹具或状态准备若妨碍接口级测试，应仅调整相关场景，不把内部数据访问作为产品接口验收标准。

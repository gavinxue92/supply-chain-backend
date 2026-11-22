## 架构宪法

- 后端采用 `controller`、`service` / `service.impl`、`mapper`、`entity`、`dto`、`vo` 的标准包结构和四层职责边界。
- `controller` 仅处理 HTTP 路由、`@Valid` 参数校验、调用 Service 和返回 `Result<T>`；禁止在 Controller 中编写业务逻辑、数据库访问或安全计算。
- 核心业务规则、事务、密码校验和限流调度由 Service 层负责。
- 持久层统一使用 MyBatis-Plus；Mapper 继承 `BaseMapper<T>`，查询优先使用 `LambdaQueryWrapper` 和参数化 Mapper 接口。禁止新增原生 `JdbcTemplate` 和手工拼接 SQL。
- `entity`、`dto`、`vo` 分别表示数据库实体、请求 DTO 和响应 VO；API 不得直接返回 `Map<String, Object>` 或数据库实体。
- `Result<T>`、全局异常处理器（`@RestControllerAdvice`）放在 `common` 包；安全认证过滤器放在 `security` 包。
- 新增或修改代码必须遵循以上边界；重构已有代码时按垂直切片逐步迁移，不得在新功能中继续扩大违反规范的实现。
- 新增代码使用的第三方 API 必须在 `pom.xml` 中声明直接依赖，不得依赖偶然的传递依赖；按运行/测试用途设置 scope，并在每个实现任务中同步清理确认无用的直接依赖。新增/调整依赖后验证 Maven 解析和编译。
- 架构重构已完成（2026-11-19 前）：认证、账号、分类、产品、媒体各切片均已迁入标准四层。后续新增功能直接按上述边界写，不再回到旧结构。
- 接口测试通过 HTTP 调用应用，检查状态码、响应体与可观察结果；不得把 Controller、Service 或 Mapper 的内部实现当验收面。只调整行为确实变化的那部分测试，不扩大范围。

## 跨仓库协作

本服务端与另外两个仓库同属一个产品，独立仓库、独立部署，不要试图合并：

- 运营管理端：`D:\project\supply-chain-admin`（Vue 3），只调用 `/api/v1/admin/**`。
- 小程序：`D:\project\supply-chain-miniapp`（原生微信小程序），只调用公开的 `/api/v1/**`。

规则：

- 改 `Admin*Controller` 前，先读 `D:\project\supply-chain-admin\src\api\*.ts` 与 `src/types/*.ts`，确认字段名与调用点；改 `Public*Controller` 前，先读 `D:\project\supply-chain-miniapp\api\catalog.ts` 与 `types\catalog.ts`。Agent 不会自动去读兄弟仓库，必须显式打开这些文件。
- 接口变更单必须有一列「影响面」，取值为「仅管理端 / 仅小程序 / 两端 / 都不影响」，并写出对应文件路径。
- 跨端的数据耦合只有三处，改动前两端都要看：`detailHtml` 清洗白名单 ↔ 小程序 `rich-text`；`/api/v1/media/{id}` 相对路径 ↔ 管理端 `src/utils/product-html.ts` 与小程序基址拼接；`global_contact_configs` ↔ 两端客服展示与小程序 `config/shop.ts` 兜底。这三处没有编译期检查，改错只在真机显现。
- 接口字段以 `GET /api/v1/openapi` 为唯一真源，前端手写类型是抄本；每次改接口后重新导出 `docs/openapi.json` 并提交。
- 跨端需求在 `D:\project\supply-chain\` 工作区开会话（`backend/` = 本仓，`admin/`、`miniapp/` 为兄弟仓库的 junction）；只改本仓的单端需求仍直接在本仓会话。

## 文档索引

| 文档 | 位置 | 内容 |
|---|---|---|
| 架构宪法（本文件） | `AGENTS.md` | 分层、边界、测试接缝 |
| 接口契约 | `D:\project\supply-chain-miniapp\docs\api-contract.md` | 路径、字段、错误约定、展示规则 |
| 契约快照 | `docs/openapi.json` | 运行时导出的 OpenAPI，唯一真源 |
| 产品与业务规则 | `D:\project\supply-chain\docs\PRODUCT.md` | 角色、状态机、15 条业务规则 |
| 决策与约束 | `D:\project\supply-chain\docs\DECISIONS.md` | 21 条决策 + 已作废结论 |
| 流程与阶段门 | `D:\project\supply-chain\docs\FLOW.md` | 变更分级、G0–G7、验收命令 |
| Prompt 卡片 | `D:\project\supply-chain\docs\PROMPTS.md` | 含接口演进模板（P4） |
| 发布与更新 | `docs/DEPLOY.md` | 备份、增量 DDL、换码、验证、回滚 |
| 上线脚本 | `ops/README.md` | 服务器执行 `ops/update.sh`（后端）、`ops/admin.sh`（管理端）、`ops/deploy.sh`（全量）的用法 |

导出契约快照：

```bash
curl -s http://localhost:8080/api/v1/openapi -o docs/openapi.json
```

预期：无输出，文件约 30 KB。

历史文档（旧 ADR、已完成的重构计划、旧版前端对接文档）已归档到 `D:\project\supply-chain\.archive\backend\`，不要参考。

## 运维脚本冻结

`ops/` 下的 `deploy.sh`、`update.sh`、`admin.sh`、`docker-compose.lite.yml`、`.env.example` 直接操作线上容器、数据库与静态站点，随后端仓库下发到服务器 `/opt/mattTest/ops/`。

- **只有用户明确说「更新部署脚本」「更新脚本」或「更新管理端脚本」时，才允许修改这五个文件。** 修 bug、加接口、调字段时不得顺带改。
- 新增迁移 SQL 不算改脚本：按 `ops/README.md` 第五节要求（幂等、按日期命名）放进 `scripts/migrations/`，`update.sh` 会自动发现并记录到 `schema_migrations` 表。
- 迁移脚本必须幂等，因为执行失败不会写入记录、下次会重跑；MySQL 8 加列要用 `information_schema` 判断后再 `PREPARE`，模板见 `ops/README.md`。

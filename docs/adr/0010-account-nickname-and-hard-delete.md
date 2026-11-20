# ADR-0010：账号昵称与停用后硬删除

- **状态**：已接受
- **日期**：2026-11-19
- **取代关系**：部分取代 ADR-0007 中“管理员管理账号”的默认范围

## 背景

运营后台需要展示更友好的操作者名称，并在账号不再使用时彻底移除数据。原模型只有用户名、角色和停用状态，没有昵称，也没有终态删除。开发阶段允许清库重建，不需要兼容旧库数据。

## 决策

- `admin_users` 增加可选、非唯一的 `nickname`，最多 50 字符。
- 管理员和运营编辑均可通过 `PATCH /api/v1/admin/accounts/me/nickname` 修改自己的昵称；管理员还可通过 `PATCH /api/v1/admin/accounts/{id}/nickname` 修改他人昵称。
- 硬删除为两步显式流程：账号必须先停用，再由管理员调用 `DELETE /api/v1/admin/accounts/{id}`。禁止删除自己和最后一个启用管理员，违反时返回 `409`。
- 账号硬删除为物理删除；会话先物理清除；`audit_logs.actor_admin_user_id` 与 `media_resources.uploaded_by` 使用 `ON DELETE SET NULL`。
- 所有审计摘要必须写入操作者用户名和昵称快照，使账号删除后仍可追溯操作者。
- 新增审计动作：`ADMIN_ACCOUNT_NICKNAME_UPDATED`、`ADMIN_ACCOUNT_HARD_DELETED`。

## 后果

- 管理员账号不再仅依赖 `is_deleted` 表达删除；旧字段保留仅为兼容统一审计列，不参与正常查询。
- 审计记录保留操作者快照，不记录密码、令牌或完整手机号。
- 生产环境若需保留历史数据，须另行提供迁移脚本；本次开发迭代不提供。

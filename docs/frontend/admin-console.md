# 运营管理端前端开发与接口对齐

本文面向运营管理后台开发人员，约定浏览器端的认证、权限、产品/分类/媒体/客服/账号管理接口及表单行为。后端接口为 `/api/v1` 下的 REST/JSON；请求字段以当前 DTO、响应字段以 VO 为准。

## 角色与功能边界

| 角色 | 可用范围 |
|---|---|
| `ADMIN`（管理员） | 产品和分类管理、产品客服、全局客服、后台账号管理；可归档及恢复产品 |
| `EDITOR`（运营编辑） | 产品、分类、媒体及产品级客服管理；不能管理后台账号、全局客服，不能归档或恢复产品 |

后台账号与小程序客户身份独立；没有公开注册或自助找回密码。草稿、上架、下架、归档是产品状态，不是账号权限。

## 浏览器会话与 CSRF

后台使用服务端可撤销的 Cookie 会话，不使用 Bearer token。浏览器请求须携带 Cookie（例如 `fetch` 使用 `credentials: "include"`）；不要把会话凭证写入 Local Storage、Session Storage 或 URL。会话 Cookie 为 `HttpOnly`、`SameSite=Strict`，配置默认启用 `Secure`，空闲 12 小时过期。

所有写请求（包括登录）都要先取得 CSRF token，并在请求头 `X-XSRF-TOKEN` 中发送：

1. `GET /api/v1/admin/auth/csrf`，启用凭证发送，响应为 `{ "success": true, "data": { "token": "..." }, "message": null }`。
2. 将 `data.token` 放入每个 `POST`、`PUT`、`PATCH`、`DELETE` 请求的 `X-XSRF-TOKEN` 头；同时发送 `Content-Type: application/json`（对象存储直传除外）。
3. 登录成功后由浏览器保存后端设置的 HttpOnly 会话 Cookie。跨域部署必须启用凭证式 CORS，并将确切的后台 Origin 配入后端；由于 Cookie 使用 `SameSite=Strict`，前后端应部署在兼容的同站点域名下，或通过同站点反向代理接入。不要以放宽前端设置替代后端域名配置。

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/api/v1/admin/auth/csrf` | 取得 CSRF token |
| `POST` | `/api/v1/admin/auth/login` | 登录，JSON：`{ "username": "...", "password": "..." }` |
| `POST` | `/api/v1/admin/auth/logout` | 登出并撤销当前会话 |
| `POST` | `/api/v1/admin/auth/change-password` | 首次登录改密，JSON：`{ "newPassword": "..." }` |

登录成功的 `data` 为 `{ username, role, mustChangePassword }`。如果 `mustChangePassword` 为 `true`，必须先完成改密；后台其他管理 API 在改密前不可用。新密码长度为 12–200 字符。改密成功后当前 Cookie 会清除，要求重新登录。不要把登录响应当成会话 token。

CSRF/CORS、失效会话、权限拒绝等可能返回 `401` 或 `403`；连续五次登录密码错误会触发短暂锁定，锁定时长由后端配置。当前没有“获取当前登录用户”接口；页面刷新后不要依赖前端缓存恢复认证状态，可在调用受保护 API 时根据 `401` 结束会话并跳回登录页。

## 统一响应与错误处理

JSON API 返回：

```json
{ "success": true, "data": {}, "message": null }
```

写操作成功也应检查 `success`；无响应体业务数据的操作返回 `data: null`。常见状态码：`400` 参数或业务规则不通过、`401` 未认证、`403` 权限不足、`404` 资源不存在、`409` 状态冲突/资源重复、`410` 上传凭证过期、`500` 未预期服务错误。当前错误体仍为 `Result`（`success: false`、`data: null`、`message`），没有稳定的机器可读错误码；用 HTTP 状态码和通用提示处理，不依赖 `message` 文案。

列表接口当前均返回完整数组，没有分页、筛选或排序参数。日期时间为 ISO-8601 字符串；金额使用 JSON 数字，传入时最多两位小数。

## 产品管理

| 方法 | 路径 | 用途/权限 |
|---|---|---|
| `GET` | `/api/v1/admin/products` | 获取管理产品列表（无分页，按后端顺序） |
| `GET` | `/api/v1/admin/products/{id}` | 获取产品 |
| `POST` | `/api/v1/admin/products` | 创建草稿，返回 `201` |
| `PUT` | `/api/v1/admin/products/{id}` | 替换并保存产品内容 |
| `POST` | `/api/v1/admin/products/{id}/publish` | 上架 |
| `POST` | `/api/v1/admin/products/{id}/unpublish` | 下架 |
| `POST` | `/api/v1/admin/products/{id}/archive` | 归档，仅管理员 |
| `POST` | `/api/v1/admin/products/{id}/restore` | 恢复为下架，仅管理员 |

`ProductDraftVO`：

```ts
type ProductDraft = {
  id: number;
  status: "DRAFT" | "PUBLISHED" | "UNPUBLISHED" | "ARCHIVED";
  name: string | null;
  categoryId: number | null;
  detailText: string | null; // 后端返回已清洗 HTML
  mainImageMediaId: number | null;
  galleryMediaIds: number[];
  keywords: string[];
  contactMode: "INHERIT" | "OVERRIDE" | "DISABLED";
  contactWechatId: string | null;
  contactQrMediaId: number | null;
  sortOrder: number;
  specifications: { id: number; name: string; referencePrice: number | null; sortOrder: number }[];
};
```

创建和更新请求共用 `ProductDraftRequest`，不传响应中的 `id`、`status`、规格 ID 或规格排序值：

```json
{
  "name": "冷冻带鱼",
  "categoryId": 12,
  "mainImageMediaId": 301,
  "galleryMediaIds": [302, 303],
  "detailText": "<p>产品说明</p><p><img src=\"/api/v1/media/304\" alt=\"展示图\"></p>",
  "keywords": ["带鱼", "冻带鱼"],
  "contactMode": "INHERIT",
  "contactWechatId": null,
  "contactQrMediaId": null,
  "sortOrder": 0,
  "specifications": [
    { "name": "500g", "referencePrice": 18.5 },
    { "name": "1kg", "referencePrice": null }
  ]
}
```

- `PUT` 是整份产品内容替换，不是局部 Patch。更新时把完整表单状态一起提交；尤其 `specifications`、`keywords`、`galleryMediaIds` 传 `null` 或省略会清空对应内容，明确提交 `[]` 表示空列表。
- `name` 最多 200 字符；`galleryMediaIds` 最多 8 个。规格名称必填、最多 120 字符；参考价可空，否则必须大于 0 且精确到分。`sortOrder` 未填写时后端按 0 处理。
- 保存已上架产品会立即对小程序生效，不存在待发布版本。上架要求名称、启用末级分类、已确认主图和至少一个规格；价格可为空。上架失败通过 `400` 返回，应保留用户输入并显示可操作的校验提示。
- `INHERIT` 使用当前全局客服；`OVERRIDE` 必须填写产品自己的微信号和/或二维码，整体覆盖全局配置；`DISABLED` 关闭入口。非 `OVERRIDE` 时 `contactWechatId` 和 `contactQrMediaId` 必须为空。建议表单始终显式发送三个客服字段，避免隐式默认。
- 状态流转：`DRAFT`/`UNPUBLISHED` 可上架；`PUBLISHED` 可下架；管理员可归档非归档产品，恢复后为 `UNPUBLISHED`。归档产品只能恢复，不能普通编辑；恢复后须重新检查再上架。
- 后端返回的详情 HTML 已清洗。编辑器仅允许兼容的段落、换行、标题、列表、粗体/斜体、图片；图片 `src` 必须采用 `/api/v1/media/{id}`，仅引用当前操作者已确认的媒体。不得依赖外部图片地址、任意 CSS、脚本或 iframe。

## 分类管理

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/api/v1/admin/categories` | 获取所有分类（包括停用项），无分页 |
| `GET` | `/api/v1/admin/categories/{id}` | 获取单项 |
| `POST` | `/api/v1/admin/categories` | 创建，返回 `201` |
| `PUT` | `/api/v1/admin/categories/{id}` | 更新名称、父分类、排序 |
| `PATCH` | `/api/v1/admin/categories/{id}/status` | 启用/停用，JSON：`{ "active": false }` |
| `DELETE` | `/api/v1/admin/categories/{id}` | 删除无子分类且无关联产品的分类 |

`CategoryVO` 为 `{ id, parentId, name, active, sortOrder }`。后台列表是扁平数组；前端可按 `parentId` 组织树。`CategoryRequest` 为 `{ name, parentId, sortOrder }`，其中 `name` 必填且最多 120 字符，一级分类的 `parentId` 为 `null`，`sortOrder` 必填。最多两级、同一父级下名称不能重复；有子分类或产品时删除返回 `409`，应提示停用而不是删除。停用一级分类会隐藏整棵树，但不改变子分类/产品自身状态。

## 媒体上传

图片上传由管理 API 授权，浏览器再将文件直传对象存储，最后确认登记；不能把图片文件作为普通 JSON 上传到业务 API。

1. 调用 `POST /api/v1/admin/media/uploads`，请求 `{ "originalFilename": "photo.webp", "contentType": "image/webp" }`。允许 MIME 类型仅 `image/jpeg`、`image/png`、`image/webp`。
2. 从 `data` 读取 `{ mediaId, uploadUrl, method, headers, expiresAt }`，按返回的 `method`、`uploadUrl` 和 `headers` 使用原始文件字节直传；不要对上传 URL 附加管理 API Cookie，也不要漏传响应要求的头。
3. 成功上传后调用 `POST /api/v1/admin/media/{mediaId}/confirm` 确认。返回 `{ id, originalFilename, mediaType, byteSize, status, publicUrl }`；只有确认成功的媒体可在产品或客服表单中引用。

单张文件最大 10 MiB；服务端会校验大小和真实图像内容。上传凭证有效期短（后端配置，最多 15 分钟），过期或上传失败时重新申请。产品主图恰一张，轮播图最多八张；详情图、二维码也都引用已确认媒体 ID。图片公开 URL 可免登录读取，但 bucket 本身不公开。

## 客服配置与后台账号（仅管理员）

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/api/v1/admin/contact/global` | 读取全局客服 |
| `PUT` | `/api/v1/admin/contact/global` | 保存全局客服，JSON：`{ "wechatId": "...", "qrMediaId": 301 }` |
| `GET` | `/api/v1/admin/accounts` | 获取账号列表 |
| `POST` | `/api/v1/admin/accounts` | 创建账号，返回 `201` |
| `POST` | `/api/v1/admin/accounts/{id}/deactivate` | 停用账号并撤销会话 |
| `PUT` | `/api/v1/admin/accounts/{id}/temporary-password` | 重置临时密码并撤销会话 |

全局客服响应为 `{ id, wechatId, qrMediaId, qrImageUrl }`；初始未配置时 `data` 可为 `null`。保存时微信号和二维码至少一项有效，二维码须为已确认媒体。保存立即影响所有继承全局客服的上架产品；产品覆盖和关闭策略不变。

创建账号请求为 `{ "username": "...", "temporaryPassword": "...", "role": "ADMIN" | "EDITOR" }`；用户名不得含空白、最多 80 字符，临时密码长度 12–200。重置密码请求为 `{ "temporaryPassword": "..." }`。账号响应为 `{ id, username, role, active, mustChangePassword, createdAt }`。管理员重置密码后，目标账号下次登录必须改密。

## 联调检查清单

- 登录、所有写接口和退出均携带当前 `X-XSRF-TOKEN`；浏览器允许 Cookie 凭证，并配置正确的管理端 Origin。
- 对 `401` 清理前端登录态；对 `403` 展示权限/改密提示，不无限自动重试。
- 提交表单时保留后端返回的状态枚举及媒体 ID；不要以 UI 状态替代服务端成功响应。
- 图片上传按“授权 → 对象存储 PUT → 确认”完成；确认前不把资源 ID 写入产品内容。
- 上架、删除分类、编辑已上架产品、切换一级分类状态、全局客服更新等操作应给出明确确认/结果反馈。

## 对齐依据与未提供能力

依据：`.scratch/supply-chain-backend/spec.md`、`docs/adr/0002-api-boundaries.md` 至 `docs/adr/0009-delivery-and-testing.md`，以及当前 `controller`、`dto`、`vo` 与服务实现。

当前没有分页、当前用户查询、审计记录查询、批量导入、富文本编辑器 API、单独产品客服接口或媒体删除接口。不要仅凭产品需求说明假设这些端点已存在。后端错误文案、分页细节及生产域名需以部署配置和 OpenAPI 3 契约进一步对齐；Swagger UI 仅非生产环境开放。

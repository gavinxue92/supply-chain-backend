# 小程序用户端前端开发与接口对齐

本文面向小程序开发人员，定义免登录食材目录的页面范围、接口契约和展示规则。接口字段以当前后端 Controller、DTO/VO 实现为准；未实现的功能不应由前端自行假设。

## 产品边界与页面范围

小程序用户无需注册或登录，可以查看可见分类、已上架食材、规格参考价和客服联系方式。建议页面流程为：

1. 分类首页：展示一级分类及可见的二级分类。
2. 产品列表：从分类进入，或从搜索结果进入；支持按关键词搜索和分类筛选。
3. 产品详情：展示主图、轮播图、图文详情、规格、参考价提示及可用的客服联系方式。

本期不包含购物车、下单、支付、库存、客户账号或微信客服会话接入。规格是咨询选项，不是可购买 SKU。

## 接口约定

所有 JSON API 使用 `/api/v1` 前缀。公开目录及图片接口不需要后台会话。JSON 接口统一返回：

```json
{
  "success": true,
  "data": {},
  "message": null
}
```

失败时 HTTP 状态码与 `success: false`、`data: null`、`message` 一起处理；不得只根据 HTTP 200 判定业务成功。成功列表直接放在 `data` 中。当前目录接口没有分页参数，返回完整列表；列表为空时 `data` 为 `[]`。

| 方法 | 路径 | 用途 | `data` |
|---|---|---|---|
| `GET` | `/api/v1/categories` | 获取可见分类树 | `PublicCategory[]` |
| `GET` | `/api/v1/categories/{id}/products` | 获取该分类下产品；一级分类包含其可见二级分类的产品 | `PublicProductSummary[]` |
| `GET` | `/api/v1/products/search?q={关键词}&categoryId={分类ID}` | 搜索产品；`categoryId` 可省略 | `PublicProductSummary[]` |
| `GET` | `/api/v1/products/{id}` | 获取产品详情 | `PublicProductDetail` |
| `GET` | `/api/v1/media/{id}` | 读取已确认图片 | 图片二进制响应，不是 `Result` JSON |

搜索参数 `q` 必填、去除首尾空格后长度最多 120；`categoryId` 为正整数。请对 query string 做 URL 编码。分类或产品不存在、停用或不可见时，详情/分类查询可能返回 `404`，不要把 `404` 当成空列表。

## 数据结构与展示

```ts
type PublicCategory = {
  id: number;
  name: string;
  sortOrder: number;
  children: PublicCategory[];
};

type PublicProductSpec = {
  name: string;
  referencePrice: number | null;
  priceDisplay: string;
};

type PublicProductSummary = {
  id: number;
  name: string;
  categoryId: number;
  mainImageUrl: string;
  specifications: PublicProductSpec[];
  referencePriceNotice: string;
};

type PublicProductDetail = PublicProductSummary & {
  galleryImageUrls: string[];
  detailHtml: string;
  contact?: { wechatId: string | null; qrImageUrl: string | null } | null;
};
```

- 产品列表和详情都带规格及 `referencePriceNotice`。按后端返回的 `priceDisplay` 展示价格（非空价格形如 `¥12.30`，空价为「面议」），并展示参考价提示；不要将参考价表述为成交价或购买承诺。当前提示文案为「参考价仅供参考，实际价格以客服沟通为准」。
- 后端搜索排序为名称完全匹配、名称前缀匹配、关键词匹配、名称包含匹配，随后按运营排序权重和产品 ID 排序。列表接口按运营排序权重和产品 ID 排序。前端保留服务端顺序，不再做本地字母排序。
- 分类接口只返回启用的可见树；一级停用时其二级分类和产品均不可见。产品只归属一个二级末级分类。
- 图片 URL 是相对路径，例如 `/api/v1/media/123`。请求或渲染时与当前环境的后端基址组合；不要把开发环境域名写入数据或代码。图片接口返回实际媒体类型及图片字节，可直接用作图片源。
- `contact` 缺失或为 `null` 表示客服入口关闭或当前无可展示的全局客服配置；非空对象中的微信号、二维码 URL 也可能各自为 `null`。只展示存在的联系渠道。复制微信号时提供明确的复制成功/失败反馈；不得伪造或硬编码客服信息。
- `detailHtml` 是后端清洗后的受限 HTML，仅包含基础段落、标题、列表、强调和系统媒体图片。使用小程序兼容的富文本组件展示，不要用 WebView 执行内容，不要自行拼接可执行脚本或注入外链图片。服务端白名单清洗仍是最终安全边界。

## 小程序开发注意事项

- 小程序不调用 `/api/v1/admin/**`，不保存或发送后台会话、CSRF 凭证。
- 将后端 API 基址按开发、测试、生产环境配置；线上使用 HTTPS，并按微信平台要求配置合法 request/download 域名。
- 保持列表请求的服务端排序；用户切换搜索词或分类时，以最新响应更新结果，避免旧请求覆盖新结果。
- 搜索空值不发送请求；清空搜索时回到分类/目录浏览，不把空字符串当作有效 `q`。
- 处理网络失败、非成功 HTTP 状态、`success: false`、空结果和图片加载失败状态。当前错误 `message` 为后端诊断文案，不是稳定错误码；前端可显示通用可理解提示，不应依赖具体英文文案分支。

## 当前契约边界

- 当前没有分页、排序参数、筛选条件扩展、商品详情分享参数或客户端身份接口；不要在联调中按未实现契约开发。
- API 前缀和字段若有调整，以同版本后端 OpenAPI 3 契约及实际接口变更为准。Swagger UI 只在非生产环境开放。
- 相关依据：`.scratch/supply-chain-backend/spec.md`、`docs/adr/0002-api-boundaries.md`、`docs/adr/0003-category-model.md`、`docs/adr/0004-product-and-contact-rules.md`、`docs/adr/0005-object-storage.md`、`docs/adr/0006-rich-text.md`。

# ADR-0011：客服实体、产品挂载与手机号

- **状态**：已接受
- **日期**：2026-11-19
- **取代关系**：部分取代 ADR-0004 的“单一客服配置/每配置一个微信号与二维码”

## 背景

运营需要维护多个可复用客服联系人，并将不同客服挂载到不同产品，供小程序客户查看和联系。原模型把客服信息作为全局单行配置与产品上的两个字段，无法表达多客服。

## 决策

- 新增独立聚合根“客服联系人”：`display_name`、微信号、二维码媒体 ID、手机号、启用状态、审计列。每项至少提供微信号、二维码或手机号之一。
- 管理员创建、编辑、停用与硬删除客服；运营编辑只能把已启用客服挂载到产品、解挂或调整顺序。
- `global_contact_configs` 保留为 GLOBAL 入口，通过 `global_contact_services` 有序关联客服列表。
- products 删除专属微信/二维码列，改为 `contact_mode` 加 `product_contact_services` 有序关联。
- `INHERIT` 解析全局客服列表；`OVERRIDE` 只使用挂载列表；`DISABLED` 不展示入口。策略仍整体覆盖，不逐字段混合。
- 手机号是客服联系渠道之一，可选、非唯一、不用于登录或找回，按中国大陆手机号格式校验，可向小程序客户展示；审计摘要只记录是否配置或后四位。
- 小程序 `contact` 契约改为 `{ services: [{ displayName, wechatId, qrImageUrl, phone }] } | null`，仅返回启用客服，按挂载顺序排列。
- 新增审计动作：`CUSTOMER_SERVICE_CREATED`、`CUSTOMER_SERVICE_UPDATED`、`CUSTOMER_SERVICE_DEACTIVATED`、`CUSTOMER_SERVICE_DELETED`、`GLOBAL_CONTACT_SERVICES_UPDATED`、`PRODUCT_CONTACT_SERVICES_UPDATED`。

## 后果

- “多客服分流”不再是范围外能力，但本次仍不接入微信客服/企业微信会话。
- 客服二维码是新的媒体引用来源，必须纳入媒体删除引用计数。
- 停用客服不改写既有挂载关系，只在管理挂载和公开展示时过滤。

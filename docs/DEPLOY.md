# 发布与更新（现有服务器）

目标机器：`212.64.84.45`，仓库在 `/opt/mattTest`，编排文件 `docker-compose.lite.yml`，数据在 `mysql-data` / `minio-data` 两个卷里。

> 镜像构建时带 `-DskipTests`，**测试只在本地跑**。服务器上编译失败才会拦住你，逻辑错误拦不住。

## 0. 先决条件（本地）

代码必须已在 `main` 上提交并推送，因为服务器用 `git pull --ff-only` 更新——本地没推的改动永远上不去。

```bash
cd /d/project/supply-chain/backend && git status -sb
```

预期：第一行 `## main...origin/main` 后面**没有** `[ahead N]`。

```bash
cd /d/project/supply-chain/backend && mvn -q -DskipTests package
```

预期：无输出、退出码 0，生成 `target/supply-chain-backend-0.1.0-SNAPSHOT.jar`（约 50 MB）。

管理端与小程序也要能构建（它们跟着后端一起发）：

```bash
cd /d/project/supply-chain/admin && npm run build
```

```bash
cd /d/project/supply-chain/miniapp && npm run verify
```

**Windows 构建陷阱**：如果本机还跑着一个旧的后端实例，`target/` 里的 jar 被占用，Maven 会报
`Unable to rename ...jar to ...jar.original`，产物只有 198 KB（不是真 jar）。先确认没有残留进程：

```powershell
Get-Process java -ErrorAction SilentlyContinue | Select-Object Id, StartTime
```

有就结束它再构建，或直接 `Stop-Process -Id <PID> -Force`。**198 KB 的 jar 一定不对**，看到这个数字就停下排查。

## 1. 本次变更（2026-11-22 v3）

| 层 | 变更 | 是否需要动作 |
|---|---|---|
| 数据库 | 新增 `shop_info`、`search_hot_keywords` 两张表，**无任何 ALTER** | 执行一次迁移脚本 |
| 后端 | 新增 5 个公开端点 + 2 个管理端点；`SecurityConfiguration` 放行新公开路径 | 重建镜像 |
| 管理端 | 新增「店铺信息」「搜索热词」两页 | 本地 `npm run build`，上线另行处理 |
| 小程序 | 搜索页常搜/推荐、我的页店铺信息改走接口 | 上传新版本体验版 |

新公开端点：`GET /api/v1/products`、`GET /api/v1/products/recommended`、`GET /api/v1/catalog/bootstrap`、`GET /api/v1/search/hot-keywords`、`GET /api/v1/shop`
新管理端点：`GET|PUT /api/v1/admin/shop`、`GET|PUT /api/v1/admin/search/hot-keywords`

## 2. 备份（服务器，约 1 分钟）

```bash
cd /opt/mattTest && docker compose -f docker-compose.lite.yml exec -T mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines supply_chain' > /opt/backup-$(date +%F-%H%M).sql
```

预期：无输出。随后确认文件非空（正常几十 KB 到几 MB）：

```bash
ls -lh /opt/backup-*.sql | tail -3
```

记录当前提交，回滚时要用：

```bash
cd /opt/mattTest && git log --oneline -1
```

预期：输出现有提交号（当前应为 `34cbd7a`）。

## 3. 数据库增量（服务器，约 1 分钟）

**顺序很重要：先加表，再换代码。** 旧代码不认识新表，加了也无害；反过来新代码打到旧库会 500。

第一步先拉代码（迁移脚本随仓库下发，不用手工传文件）：

```bash
cd /opt/mattTest && git pull --ff-only
```

预期：`Fast-forward`，并列出新增的 `scripts/migrations/` 等文件。若报 `diverging branches` 或 `local changes would be overwritten`，停下来，别强推，先查服务器上改过什么。

执行迁移：

```bash
cd /opt/mattTest && docker compose -f docker-compose.lite.yml exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" supply_chain' < scripts/migrations/2026-11-22-v3-shop-and-hot-keywords.sql
```

预期：无输出（`CREATE TABLE IF NOT EXISTS` 与 `ON DUPLICATE KEY` 都是静默成功的）。

校验表数量与种子数据：

```bash
cd /opt/mattTest && docker compose -f docker-compose.lite.yml exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "SHOW TABLES" supply_chain' | wc -l
```

预期：`15`（原 13 + 新增 2）。

```bash
cd /opt/mattTest && docker compose -f docker-compose.lite.yml exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SELECT COUNT(*) AS hot FROM search_hot_keywords WHERE is_deleted=0; SELECT name,phone FROM shop_info" supply_chain'
```

预期：`hot` = 6；`shop_info` 一行，`小陈食品` / `13800006688`。

## 4. 更新应用（服务器，首次构建 10–20 分钟）

2 核 2G 机器编译慢是正常的，别中断。构建期间旧容器仍在服务，最后一步才替换，中断时间只有几秒。

```bash
cd /opt/mattTest && docker compose -f docker-compose.lite.yml up -d --build app
```

预期：结尾出现 `Container mattTest-app-1  Started`（名称可能带项目前缀）。

等健康检查：

```bash
for i in $(seq 1 40); do curl -fsS -m 3 http://127.0.0.1:8080/api/v1/health && break || sleep 5; done
```

预期：`{"success":true,"data":{"status":"UP"}}`。

看启动日志是否有异常：

```bash
cd /opt/mattTest && docker compose -f docker-compose.lite.yml logs --tail 60 app
```

预期：出现 `Started SupplyChainApplication`，没有 `ERROR` 堆栈。

## 5. 验证（本地或服务器，约 3 分钟）

先在本地打这几个地址，**注意线上是旧版时它们都是 401**，部署后应变 200：

```bash
curl -s -m 8 http://212.64.84.45:8080/api/v1/search/hot-keywords
```

预期：`{"success":true,"data":["雪花牛肉","牛毛肚","鸡爪","牛肉","毛肚","猪肉"]}`

```bash
curl -s -m 8 http://212.64.84.45:8080/api/v1/shop
```

预期：`{"success":true,"data":{"name":"小陈食品",...}}`（含 slogan/address/hours/phone/wechat）

```bash
curl -s -m 8 "http://212.64.84.45:8080/api/v1/products?page=1&pageSize=2"
```

预期：`{"success":true,"data":{"items":[...],"total":N,"page":1,"pageSize":2}}`

回归检查（老接口必须一模一样）：

```bash
curl -s -m 8 http://212.64.84.45:8080/api/v1/categories | head -c 200
```

预期：与部署前相同的分类树 JSON，字段一个不少。

契约快照对比：

```bash
curl -s -m 8 http://212.64.84.45:8080/api/v1/openapi -o /tmp/openapi-after.json && grep -o '"/api/v1[^"]*"' /tmp/openapi-after.json | sort -u | wc -l
```

预期：`40`（部署前 33，新增 7 条路径）。

## 6. 前端

**管理端**（本地即可，目前没上服务器）：

```bash
cd /d/project/supply-chain/admin && npm run build
```

预期：`built in ...s`，`dist/` 生成。要上线再按 `D:\project\deploy\nginx\admin.conf` 部署 `dist`，并确认 `.env` 里 `APP_ALLOWED_ORIGINS` 已含该域名。

**小程序**：开发者工具「上传」→ 公众平台设为体验版 → 真机「打开调试」。
重点看三处：搜索页「常搜」是否有 6 个词、「为你推荐」是否有 2 条、我的页店铺信息是否来自接口（不再是写死值）。

## 7. 回滚

关键前提：**本次只新增表，没有 ALTER，所以代码回滚不需要动数据库**。新表留着不影响旧代码。

```bash
cd /opt/mattTest && git log --oneline -5
```

预期：找到部署前的提交号（§2 记录的那个）。

```bash
cd /opt/mattTest && git checkout <旧提交号>
```

预期：`HEAD is now at ...`。

```bash
cd /opt/mattTest && docker compose -f docker-compose.lite.yml up -d --build app
```

预期：重新构建并启动旧版本，健康检查恢复。

数据要回滚（极少需要）才动备份，会丢部署后的新增数据：

```bash
cd /opt/mattTest && docker compose -f docker-compose.lite.yml exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" supply_chain' < /opt/backup-2026-11-22-XXXX.sql
```

预期：无输出。**执行前先想清楚是否真要丢这段时间的数据。**

## 8. 出问题时按这个顺序查

| 现象 | 先查什么 |
|---|---|
| 部署后 `/shop` 仍 401 | 镜像没换成功：`docker compose ps` 看 app 容器启动时间是否刚更新 |
| `/shop` 返回 404 | `shop_info` 没数据：执行 §3 的第二条校验 SQL |
| 页面 500，日志有 `Table doesn't exist` | 迁移没执行或执行到了别的库：确认连的是 `supply_chain` |
| 管理端登录 403 | `APP_ALLOWED_ORIGINS` 不含当前前端地址，或 Cookie 的 `SameSite` 与跨域冲突 |
| 图片裂 | `APP_MEDIA_PUBLIC_BASE_URL` 是否仍是 `http://212.64.84.45:8080` |
| 构建卡住或 OOM | `free -h` 看 swap；2G 机器构建时不要同时跑别的容器 |

## 9. 顺带该做的事

- 分类名「35鸡爪」疑似把规格数字录进了名称，在管理端改成「鸡爪」即可，不用发版。
- `D:\project\deploy` 仍不在任何 git 仓库，nginx 配置与上线清单只存在本机。建议并入本仓 `deploy/` 或单独建仓。
- 正式版仍缺：已备案域名 + HTTPS + 公众平台合法域名配置。当前只支持体验版。

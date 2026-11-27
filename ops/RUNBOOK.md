# RUNBOOK —— 发版执行清单

服务器 `212.64.84.45`，代码目录 `/opt/mattTest`。

这份文件只做一件事：**按顺序抄命令**。为什么这么做的原理写在 [README.md](./README.md)，这里不重复。

每一步都标了执行位置，别搞混：

| 标记 | 含义 |
|---|---|
| **每次** | 日常发版都要跑 |
| **仅本次** | 这次发布特有的一次性动作，做完打勾，以后不要再跑 |
| **本地** | 在你自己的电脑（Windows）上执行，不是服务器 |

命令一律是单行，可直接整行复制。每行后面写了「预期看到什么」，看到的不一样就停下来，翻到第 6 节。

---

## 0. 服务器路径速查

忘了东西在哪的时候看这张表。

| 用途 | 路径 | 备份优先级 |
|---|---|---|
| 后端代码仓库根目录 | `/opt/mattTest` | 代码可从 GitHub 重建 |
| **真实密钥 `.env`** | `/opt/mattTest/.env` | **最高。不在 git 里，重装机器前必须单独拷走** |
| 编排文件（实际生效的） | `/opt/mattTest/docker-compose.lite.yml` | 可从 `ops/` 重新复制 |
| 运维脚本 | `/opt/mattTest/ops/` | 随 git 下发 |
| 迁移 SQL | `/opt/mattTest/scripts/migrations/` | 随 git 下发 |
| 管理端构建副本 | `/opt/supply-chain-admin` | 纯构建用，`reset --hard` 会丢弃本地改动 |
| 管理端静态产物（nginx root） | `/opt/admin-dist` | 有 `.bak-<时间戳>` 备份 |
| 数据库备份 | `/opt/backups/` | 保留最近 10 份 |
| 运行日志 | `/opt/logs/` | `update-*.log`、`admin-*.log`、`deploy-*.log` |
| 图片实际文件 | Docker 命名卷 `mattTest_minio-data` | 宿主机目录里看不到 |
| MySQL 数据文件 | Docker 命名卷 `mattTest_mysql-data` | 同上 |

两个容易踩的点：

- 图片和数据库都在 **Docker 命名卷**里，不在 `/opt` 的文件系统上，`ls /opt` 找不到是正常的。
- `/root` 是 root 用户主目录，跟项目无关。所有命令都基于 `/opt/mattTest`。

---

## 1. 发版前检查（本地）

- [ ] **本地**：三个仓库都推完了，没有 `ahead`

```bash
cd /d/project/supply-chain/backend && git status -sb
```
预期：`## main...origin/main`，没有 `[ahead N]`。

```bash
cd /d/project/supply-chain/admin && git status -sb
```
预期：同上。

小程序仓库同理（`cd /d/project/supply-chain/miniapp`）。若显示 `ahead` 但远端其实已同步，可能是跟踪引用过期——用 `git ls-remote https://github.com/gavinxue92/supply-chain-miniapp.git refs/heads/main` 和 `git rev-parse HEAD` 比对，一致就说明推成功了。

---

## 2. 更新后端（服务器，每次）

### 2.1 环境三查

```bash
cd /opt/mattTest && ls -la .env docker-compose.lite.yml && git log --oneline -1
```
预期：`.env` 和 `docker-compose.lite.yml` 都在，能打印出当前提交。

如果 `docker-compose.lite.yml` 不存在（只在 `ops/` 里有），补一份。**只在缺失时执行，不要覆盖已有文件**：

```bash
cp -n /opt/mattTest/ops/docker-compose.lite.yml /opt/mattTest/docker-compose.lite.yml
```
预期：无输出。

### 2.2 干跑看计划

```bash
cd /opt/mattTest && bash ops/update.sh --dry-run
```
预期：依次打印 `0/9` 到 `9/9`，每步都是 `[dry-run]`；不改任何东西。

如果报「工作区不干净」，先丢弃服务器上的已跟踪改动：

```bash
cd /opt/mattTest && git checkout -- .
```

### 2.3 正式更新

```bash
cd /opt/mattTest && bash ops/update.sh
```
预计 3–8 分钟。它会依次做：备份库 → `git pull --ff-only` → 跑未应用的迁移 → 只重建 app 容器 → 健康检查 → 接口验收。

必须看到这几行（少一行都算没成功）：

| 关键输出 | 含义 |
|---|---|
| `备份 xxx -> /opt/backups/supply_chain-*.sql` | 备份成功，失败会直接停 |
| `代码更新：xxxxxxx -> yyyyyyy` | 拉到新代码；`代码无变化` 说明没新提交 |
| `完成并已记录：<迁移文件名>` 或 `跳过（已执行）` | 迁移这两行都算正常 |
| `app 已替换` | 镜像重建完成 |
| `健康：{"success":true,...}` | 容器起来了 |
| `店铺信息 -> 200`、`搜索热词 -> 200` | 新接口通了 |
| `当前 OpenAPI 路径数：40` | 与预期基线一致 |

任何 `/XX/` 红行都会中断脚本。**脚本不会删数据**，停在哪儿查哪儿，日志在 `/opt/logs/update-*.log`。

### 2.4 存量图片转码（**仅本次**）

`update.sh` 只跑 SQL，不动对象存储里的文件。历史上传的原图是 2048×2048 无损 PNG（单张 5–6 MB），需要跑一次应用内迁移才会压成最长边 1440 的 JPEG。

**只在老图还没转的时候跑。新上传的图在上传确认时就已压好，不用管。**

```bash
cd /opt/mattTest && docker compose -f docker-compose.lite.yml exec app java -Xmx320m -jar /app/app.jar --spring.main.web-application-type=none --app.media.migrate=true
```
预期结尾：`Media migration finished: migrated=N skipped=M failed=0` 和 `saved ... bytes`。

`-Xmx320m` 不是可选项——`exec` 会在应用容器里再起一个 JVM，2 核 2G 的机器不限内存容易 OOM。

- [ ] 做完这一步就在心里划掉：**这个参数是启动开关，不能写进 `docker-compose.lite.yml` 或 `.env`**，否则每次重启都全表扫一遍。

### 2.5 核对大图是否清完（每次，只看数字）

```bash
cd /opt/mattTest && docker compose -f docker-compose.lite.yml exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "SELECT COUNT(*) FROM media_resources WHERE resource_status=\"READY\" AND media_type=\"image/png\" AND byte_size > 1000000" supply_chain'
```
预期：`0`。不是 0 就重跑 2.4（幂等，已转的会跳过）。

---

## 3. 更新管理端（服务器，每次）

管理端是 nginx 托管的静态文件，不走容器，**`update.sh` 管不到它**。这是最常见的误判：后端接口已经 200 了，管理端还是旧页面，原因就是这一节没跑。

### 3.1 后台构建

```bash
cd /opt/mattTest && bash ops/admin.sh --detach
```
预期：立刻返回 PID 和日志路径。

**为什么用 `--detach`**：2 核构建要 5–15 分钟，网页终端断线会把前台进程杀掉，构建到一半就废了。

### 3.2 看进度

```bash
tail -f /opt/logs/admin-*.log
```
预期结尾：`已发布 N 个 assets 文件` → `发布完成`。`Ctrl+C` 退出 tail，不会影响后台构建。

### 3.3 强制刷新浏览器

- [ ] 发布完必须 **Ctrl+Shift+R**，否则加载的是缓存里的旧 `index.html`

---

## 4. 上传小程序（本地，每次）

- [ ] **本地**：微信开发者工具打开 `D:\project\supply-chain\miniapp`
- [ ] 确认详情 → 本地设置里的接口域名指向 `http://212.64.84.45:8080`
- [ ] 点「上传」，版本号递增，备注写清本次改了什么

服务器上的 `admin.sh` 跟小程序无关，别指望它更新小程序。

---

## 5. 验收（服务器）

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://212.64.84.45:8080/api/v1/shop
```
预期：`200`。

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://212.64.84.45:8080/api/v1/search/hot-keywords
```
预期：`200`。

```bash
curl -s http://212.64.84.45:8080/api/v1/openapi | grep -o '"/api/v1[^"]*"' | sort -u | wc -l
```
预期：`40`。

图片档位是否生效（把 `<图片key>` 换成一个真实的 `object_key`）：

```bash
curl -s -o /dev/null -w '%{http_code} %{size_download}\n' 'http://212.64.84.45:8080/api/v1/media/<图片key>?w=320'
```
预期：`200`，体积在几万字节量级（压前是 500 万+）。

### 基线对照

| 指标 | 本次发布前 | 本次发布后 |
|---|---|---|
| OpenAPI 路径数 | 33 | 40 |
| `/api/v1/shop` | 401 | 200 |
| `/api/v1/search/hot-keywords` | 401 | 200 |
| 单图体积（列表场景） | 6,111,854 B | ~26,000 B |
| 存量图片总量 | ~50 MB | ~2.9 MB |

---

## 6. 出问题时的最短路径

按现象直接跳，完整排错表在 [README.md 第八节](./README.md#八排错)。

| 现象 | 先看这里 |
|---|---|
| 脚本中途红字退出 | `tail -50 /opt/logs/update-*.log`，`/XX/` 那行就是原因 |
| 健康检查超时 | `cd /opt/mattTest && docker compose -f docker-compose.lite.yml logs --tail 120 app` |
| 接口还是旧的 401 | 镜像没换成功：`docker compose -f docker-compose.lite.yml ps`，看 app 的启动时间是不是刚才 |
| 管理端还是旧页面 | 第 3 节没跑，或没做 Ctrl+Shift+R |
| `fast-forward 失败` | 服务器与远程分叉。**不要强推**，先 `git log --oneline origin/main..HEAD` 看服务器多了什么 |
| `bash: $'\r': command not found` | 文件被 CRLF 污染：`sed -i 's/\r$//' /opt/mattTest/ops/*.sh` |
| `admin.sh` 报找不到 nginx root | `nginx -T \| grep root` 确认目录，或首次部署加 `--init-nginx` |

---

## 7. 回滚

### 后端（代码层面，数据库不用回滚）

SHA 取 `update.sh` 输出里的「更新前提交」，或查日志：

```bash
grep '更新前提交' /opt/logs/update-*.log | tail -1
```

```bash
cd /opt/mattTest && git checkout <更新前的SHA>
```

```bash
cd /opt/mattTest && docker compose -f docker-compose.lite.yml up -d --build app
```

回滚后 HEAD 是游离状态，下次发版前切回分支：

```bash
cd /opt/mattTest && git checkout main
```

### 管理端

```bash
ls -1dt /opt/admin-dist.bak-* | head -3
```

```bash
mv /opt/admin-dist /opt/admin-dist.broken && mv /opt/admin-dist.bak-<时间戳> /opt/admin-dist
```

不需要 `nginx -s reload`，静态文件是实时读取的。浏览器硬刷新即可。

### 数据（只在确认要丢数据时）

```bash
cd /opt/mattTest && docker compose -f docker-compose.lite.yml exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" supply_chain' < /opt/backups/supply_chain-<时间戳>.sql
```

---

## 8. 这份文件本身

- 改了流程才更新它，别为了「记录本次操作」往里加东西。
- 它不属于冻结资产，但 `deploy.sh` / `update.sh` / `admin.sh` / `docker-compose.lite.yml` / `.env.example` 属于，改那些要用户明确开口（见 README 第九节）。

# ops —— 服务器运维脚本

服务器：`212.64.84.45`，代码目录 `/opt/mattTest`，编排文件 `docker-compose.lite.yml`。
这几个脚本是**唯一**的上线入口，不要在服务器上手工敲 `docker compose` 拼命令。

**这套脚本在后端仓库里**（`backend/ops/`），跟着 `git pull` 一起下发，不需要 scp。

> **发版时先看 [RUNBOOK.md](./RUNBOOK.md)** —— 那是按执行顺序排好的单行命令清单，标明了哪些步骤「每次」「仅本次」「本地」。
> 本文档讲原理和边界，排错也在这里。

## 一、文件说明

| 文件 | 什么时候用 | 会不会碰数据 |
|---|---|---|
| `RUNBOOK.md` | 发版时照着抄命令的执行清单 | —— |
| `deploy.sh` | 全新服务器、灾难恢复、要重建整套环境 | **不删数据卷**；库为空时才执行 `schema.sql` |
| `update.sh` | 日常发版：后端代码 + 数据库迁移 | 先备份；只跑增量迁移，**永不执行 `schema.sql`** |
| `admin.sh` | 日常发版：管理端页面 | 只替换静态文件，不碰数据库 |
| `docker-compose.lite.yml` | `deploy.sh` 自动复制到仓库根目录（`/opt/mattTest`） | —— |
| `.env.example` | 环境变量模板，手工填 `.env` 时照抄 | —— |

**后端和管理端是两条独立的线**：`update.sh` 只更新后端，管理端页面的改动必须另外跑 `admin.sh`。这是最常见的误判——后端接口已经 200 了，但管理端还显示旧页面。

## 二、脚本怎么到服务器

随代码下发，服务器上的位置是 `/opt/mattTest/ops/`。

**已有服务器**：什么都不用做，下一节 `git pull` 会自动更新脚本本身。

**全新机器**：还没有仓库，先 clone 一次（整个流程里唯一需要手工的一步）：

```bash
git clone https://github.com/gavinxue92/supply-chain-backend.git /opt/mattTest
```

预期：`Cloning into '/opt/mattTest'...`，结尾无报错。

### 如果之前手工传过 `/opt/ops`

早期版本曾用 `scp` 传到 `/opt/ops`，那份副本现在会和仓库里的版本分叉。确认下面这条能跑之后，把旧副本删掉：

```bash
bash /opt/mattTest/ops/update.sh --help
```

确认无误后删除旧副本（里面只有脚本副本，没有数据）：

```bash
rm -rf /opt/ops
```

## 三、首次部署后端（服务器执行）

**先确认 `.env` 存在**。`/opt/mattTest/.env` 里有真实密钥，重装机器前务必单独备份。

```bash
cd /opt/mattTest && ls -l .env
```

没有的话，脚本会生成随机密码并打印，记得抄下来：

```bash
bash /opt/mattTest/ops/deploy.sh
```

预期：依次打印 `0/8` 到 `8/8`，最后出现 `部署完成` 和 `后端健康：{"success":true,...}`。首次构建 10–20 分钟。

只想更新编排文件（改了 `docker-compose.lite.yml` 之后）：

```bash
bash /opt/mattTest/ops/deploy.sh --force-compose
```

## 四、日常更新后端（服务器执行）—— 最常用

发版前先在本地确认代码已推到 GitHub：

```bash
cd /d/project/supply-chain/backend && git status -sb
```

预期：`## main...origin/main`，**没有** `[ahead N]`。

服务器上先拉代码。**这一步单独做，不要省** —— `update.sh` 会自己 `git pull`，但脚本文件本身也在这次更新里；先拉一次能保证执行的是磁盘上最新的那份：

```bash
cd /opt/mattTest && git pull --ff-only
```

预期：`Fast-forward` 或 `Already up to date.`。

干跑看计划：

```bash
bash /opt/mattTest/ops/update.sh --dry-run
```

正式执行：

```bash
bash /opt/mattTest/ops/update.sh
```

预期依次出现：备份 → 代码更新 → `执行：<迁移文件名>` 或 `跳过（已执行）` → `app 已替换` → 健康 200 → 五个接口 `-> 200` → 回滚命令。

**脚本会自动做这些事：**

- 备份数据库到 `/opt/backups/`，只保留最近 10 份
- 检查工作区是否干净，有未提交改动直接停止
- 用 `git pull --ff-only`，绝不 `reset --hard`
- 用 `schema_migrations` 表记录已执行的迁移，**同一份只跑一次**
- 只重建 `app` 镜像，`mysql` 和 `minio` 不重启

**脚本不会做这些事：**

- 不执行 `scripts/schema.sql`（全量重建，会清空线上数据）
- 不删除数据卷、不执行 `DROP`/`TRUNCATE`
- 不更新管理端页面（那是 `admin.sh` 的活）
- 不上传小程序

## 五、更新管理端（服务器执行）

管理端是 nginx 托管的静态文件，不走容器，所以 `update.sh` 管不到它。改完管理端代码、推了 GitHub 之后跑：

```bash
bash /opt/mattTest/ops/admin.sh
```

预期：Node 就绪 → 代码同步 → `依赖安装完成` 或 `跳过安装` → `构建通过` → `已发布 N 个 assets 文件` → 首页 200 与 `/api` 反代 200。

2 核机器构建要 5–15 分钟。**如果用的是网页终端，加 `--detach` 防止断线把构建杀掉**：

```bash
bash /opt/mattTest/ops/admin.sh --detach
```

预期：立刻返回 PID 和日志路径，然后用 `tail -f` 看进度。

### 它做了什么

| 步骤 | 说明 |
|---|---|
| Node 检查 | 需要 >= 20.19；不足时从 npmmirror 下载到 `/opt/node`，不动系统 Node |
| 代码同步 | `/opt/supply-chain-admin` 是**纯构建副本**，用 `reset --hard`，上面任何本地改动都会被丢弃 |
| 依赖安装 | `package-lock.json` 的 md5 没变且 `node_modules` 在，就跳过 `npm ci` |
| 构建 | `npm run build`；类型检查失败时**降级为 `vite build`** 并打印警告 |
| 发布 | 旧目录备份成 `/opt/admin-dist.bak-<时间戳>`（保留 2 份），再替换内容 |
| 自检 | 首页 200 + `/api/v1/health` 反代 200 |

### 它不做什么

- 不改 nginx 配置。首次部署或换目录才需要 `--init-nginx`
- 不重启后端、不改 `.env`、不碰数据库
- 不刷新你的浏览器——发布完必须 **Ctrl+Shift+R 强制刷新**，否则会加载缓存里的旧 `index.html`

### 回滚管理端

```bash
ls -1dt /opt/admin-dist.bak-* | head -3
```

```bash
mv /opt/admin-dist /opt/admin-dist.broken && mv /opt/admin-dist.bak-2026-11-22-234500 /opt/admin-dist
```

预期：静态文件恢复到上一版，浏览器硬刷新即可。不需要 `nginx -s reload`（静态文件实时读取）。

## 六、新增迁移脚本的写法要求

迁移放在后端仓库的 `scripts/migrations/`，`update.sh` 会自动发现并执行。

**硬要求：必须幂等**，因为执行到一半失败不会写入记录，下次会重跑。

1. 文件名 `YYYY-MM-DD-简述.sql`，按字典序执行，同一天多条加后缀 `-2`。
2. 建表用 `CREATE TABLE IF NOT EXISTS`。
3. 插数据用 `INSERT ... ON DUPLICATE KEY UPDATE`。
4. 加列/加索引：MySQL 8 **不支持** `ADD COLUMN IF NOT EXISTS`，用下面模板。
5. 一个文件只做一件事。

```sql
SET @ddl := (SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = 'supply_chain'
             AND table_name   = 'products'
             AND column_name  = 'origin'),
    'SELECT 1',
    'ALTER TABLE products ADD COLUMN origin VARCHAR(120) NULL COMMENT ''产地'''
));
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
```

加索引把 `information_schema.columns` 换成 `information_schema.statistics`、`column_name` 换成 `index_name`。

**禁止**在迁移里写 `DROP TABLE`、`TRUNCATE`、不带 `WHERE` 的 `DELETE`。

### 6.1 一次性数据迁移：存量图片转码

`update.sh` 只跑 SQL，不动对象存储里的文件。历史上传的原图是 2048×2048 的无损 PNG（单张 5–6 MB），
需要跑一次**应用内迁移**才会压成最长边 1440 的 JPEG。新上传的图在上传确认时就已压好，不用管。

```bash
cd /opt/mattTest && docker compose -f docker-compose.lite.yml exec app java -Xmx320m -jar /app/app.jar --spring.main.web-application-type=none --app.media.migrate=true
```

`-Xmx320m` 不是可选项：`exec` 会在应用容器里再起一个 JVM，2 核 2G 的机器上不限制容易把内存挤满。
图片解码峰值约 150 MB，320m 足够；真遇到 `OutOfMemoryError` 再往上调。

预期输出：

```text
Media migration started: 7 ready resources
Media 6 migrated: 6111854 -> 376763 bytes (old key kept at confirmed/4d9abdaf-...)
Media migration finished: migrated=7 skipped=0 failed=0
Media migration saved 36800000 bytes (87% smaller)
```

四条必须知道的边界：

1. **可重复执行**：已是 JPEG 且小于 400 KB 的直接跳过，重复跑不会反复压缩。
2. **旧对象保留**：只改数据库里的 `object_key`，MinIO 里的旧图不动。要回滚就把 `object_key` 改回日志打出的旧键。
3. **`failed` 不代表数据丢失**：那是对象存储里找不到对应文件，数据库记录原样不动。先核对是历史脏数据还是 MinIO 真缺对象。
4. **参数是启动开关，不要固化**：别写进 `docker-compose.lite.yml` 或 `.env`，否则每次启动都全表扫一遍。

跑完核对还剩多少未压缩的大图：

```bash
cd /opt/mattTest && docker compose -f docker-compose.lite.yml exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "SELECT COUNT(*) FROM media_resources WHERE resource_status=\"READY\" AND media_type=\"image/png\" AND byte_size > 1000000" supply_chain'
```

预期：`0`。不是 0 就说明还有对象没转到，重复第 6.1 步即可（幂等）。

## 七、回滚后端

代码层面回滚（只新增表，旧代码不认识新表也无害）：

```bash
cd /opt/mattTest && git checkout <更新前的SHA>
```

SHA 在 `update.sh` 输出的「更新前提交」里，也在 `/opt/logs/update-*.log` 里。

```bash
cd /opt/mattTest && docker compose -f docker-compose.lite.yml up -d --build app
```

回滚后 `HEAD` 是游离状态，下次正常发版前要切回分支：

```bash
cd /opt/mattTest && git checkout main
```

数据回滚**只在确认要丢数据时**才做：

```bash
cd /opt/mattTest && docker compose -f docker-compose.lite.yml exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" supply_chain' < /opt/backups/supply_chain-2026-11-22-231500.sql
```

## 八、排错

| 现象 | 原因与处理 |
|---|---|
| 后端接口已 200，管理端还是旧页面 | 没跑 `admin.sh`。两者是独立的发布线 |
| `admin.sh` 报 `nginx 配置里没找到 root ...` | nginx 指向别的目录。`nginx -T \| grep root` 确认，或用 `--init-nginx` |
| `admin.sh` 构建 OOM 或卡住 | 2G 机器正常慢；确认有 swap（`free -h`），或用 `--detach` 后等 |
| `admin.sh` 降级到 vite build | `vue-tsc` 类型检查没过。功能能上，但类型错误要尽快修，否则会攒着 |
| 管理端登录 403 | `.env` 的 `APP_ALLOWED_ORIGINS` 不含管理端地址 |
| `compose 校验失败` | `.env` 缺必需键，脚本会列出缺哪几个 |
| `拉取失败：服务器上有未提交改动` | `cd /opt/mattTest && git status` 查，`git checkout -- .` 丢弃后重跑 |
| `fast-forward 失败` | 服务器与远程分叉。**不要强推**，先查服务器多了什么提交 |
| 迁移失败 | 修好 SQL 后重跑 `update.sh`，已成功的会跳过 |
| 健康检查超时 | `docker compose -f docker-compose.lite.yml logs --tail 120 app` |
| `/shop` 返回 401 | 镜像没换成功：`docker compose ps` 看 app 启动时间 |
| 备份文件 0 字节 | `MYSQL_ROOT_PASSWORD` 与容器不一致 |
| `bash: $'\r': command not found` | 文件被 CRLF 污染。`sed -i 's/\r$//' /opt/mattTest/ops/*.sh` |

## 九、修改规则（重要）

`backend/ops/` 下的 `deploy.sh`、`update.sh`、`admin.sh`、`docker-compose.lite.yml`、`.env.example` 是**冻结资产**。

- 只有用户明确说「更新部署脚本」「更新脚本」或「更新管理端脚本」时，才允许改。
- 实现业务功能、修 bug、调接口时，**不得**顺手改它们，也不得为了"顺手方便"加参数。
- 改之前必须说明「为什么现有脚本不够用」，改完必须用 `--dry-run` 验证一次。
- 新增迁移 SQL **不算**修改脚本。

理由：这些脚本直接操作线上数据和容器，改错一次的代价是停机加数据风险，比业务代码高一个量级。

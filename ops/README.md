# ops —— 服务器运维脚本

服务器：`212.64.84.45`，代码目录 `/opt/mattTest`，编排文件 `docker-compose.lite.yml`。
这两个脚本是**唯一**的上线入口，不要在服务器上手工敲 `docker compose` 拼命令。

**这套脚本在后端仓库里**（`backend/ops/`），跟着 `git pull` 一起下发，不需要 scp。

## 一、文件说明

| 文件 | 什么时候用 | 会不会碰数据 |
|---|---|---|
| `deploy.sh` | 全新服务器、灾难恢复、要重建整套环境 | **不删数据卷**；库为空时才执行 `schema.sql` |
| `update.sh` | 日常发版（已有线上数据） | 先备份；只跑增量迁移，**永不执行 `schema.sql`** |
| `docker-compose.lite.yml` | 脚本自动复制到仓库根目录（`/opt/mattTest`） | —— |
| `.env.example` | 环境变量模板，手工填 `.env` 时照抄；`deploy.sh` 在缺 `.env` 时自己生成随机密码，不走这个文件 | —— |

`update.sh` 是 99% 的场景。`deploy.sh` 只在机器重装、换服务器时用。

## 二、脚本怎么到服务器

随代码下发，服务器上的位置是 `/opt/mattTest/ops/`。

**已有服务器**：什么都不用做，下一节 `git pull` 会自动更新脚本本身。

**全新机器**：还没有仓库，先 clone 一次（整个流程里唯一需要手工的一步）：

```bash
git clone https://github.com/gavinxue92/supply-chain-backend.git /opt/mattTest
```

预期：`Cloning into '/opt/mattTest'...`，结尾无报错。

### 如果之前手工传过 `/opt/ops`

早期版本曾用 `scp` 传到 `/opt/ops`，那份副本现在会和仓库里的版本分叉。确认下面两条都能跑之后，把旧副本删掉，否则以后容易改错文件：

```bash
ls -1 /opt/mattTest/ops
```

```bash
bash /opt/mattTest/ops/update.sh --help
```

确认无误后删除旧副本（里面只有脚本副本，没有数据）：

```bash
rm -rf /opt/ops
```

预期：无输出。`ls -d /opt/ops` 应提示不存在。

## 三、首次部署（服务器执行）

**先确认 `.env` 存在**。`/opt/mattTest/.env` 里有真实密钥，重装机器前务必单独备份，否则数据库密码和 MinIO 密钥只能从旧盘里找。

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

## 四、日常更新（服务器执行）—— 最常用

发版前先在本地确认代码已推到 GitHub：

```bash
cd /d/project/supply-chain/backend && git status -sb
```

预期：`## main...origin/main`，**没有** `[ahead N]`。

然后服务器上先拉代码。**这一步单独做，不要省** —— `update.sh` 会自己 `git pull`，但脚本文件本身也在这次更新里；先拉一次能保证执行的始终是磁盘上最新的那份脚本：

```bash
cd /opt/mattTest && git pull --ff-only
```

预期：`Fast-forward` 或 `Already up to date.`。

干跑一遍看计划，不备份、不改库、不建镜像：

```bash
bash /opt/mattTest/ops/update.sh --dry-run
```

预期：只打印将执行的九步，末尾给出回滚命令。

确认无误后正式执行：

```bash
bash /opt/mattTest/ops/update.sh
```

预期依次出现：

1. `备份 xx MB -> /opt/backups/supply_chain-....sql`
2. `代码更新：<旧SHA> -> <新SHA>` 及提交列表（已先 pull 过则显示 `代码无变化`）
3. `执行：<迁移文件名>` 或 `跳过（已执行）`
4. `app 已替换`
5. `健康：{"success":true,...}`
6. 五个接口检查，全部 `-> 200`
7. 结尾打印回滚命令

**脚本会自动做这些事，不需要你手动操作：**

- 备份数据库到 `/opt/backups/`，只保留最近 10 份
- 检查工作区是否干净，有未提交改动直接停止（不会强拉）
- 用 `git pull --ff-only`，绝不 `reset --hard`
- 用 `schema_migrations` 表记录已执行的迁移，**同一份迁移只跑一次**
- 只重建 `app` 镜像，`mysql` 和 `minio` 容器不重启

**脚本不会做这些事：**

- 不执行 `scripts/schema.sql`（那是全量重建，会清空线上数据）
- 不删除数据卷、不执行 `DROP`/`TRUNCATE`
- 不改 `.env`、不改编排文件（除非 `deploy.sh --force-compose`）
- 不部署管理端静态文件、不上传小程序

管理端静态文件目前没上服务器，小程序要在后端验收通过后手动在开发者工具上传体验版。

## 五、新增迁移脚本的写法要求

迁移放在后端仓库的 `scripts/migrations/`（相对本文件是 `../scripts/migrations/`），`update.sh` 会自动发现并执行。

**硬要求：必须幂等**，因为：执行到一半失败不会写入记录，下次会重跑。

1. 文件名 `YYYY-MM-DD-简述.sql`，按字典序执行，同一天多条就加后缀 `-2`。
2. 建表用 `CREATE TABLE IF NOT EXISTS`。
3. 插数据用 `INSERT ... ON DUPLICATE KEY UPDATE`，不要裸 `INSERT`。
4. 加列/加索引：MySQL 8 **不支持** `ADD COLUMN IF NOT EXISTS`，用下面模板。
5. 一个文件只做一件事，失败时能单独修。

加列的可重复执行模板：

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

加索引同样处理，把 `information_schema.columns` 换成 `information_schema.statistics`、`column_name` 换成 `index_name`。

**禁止**在迁移里写 `DROP TABLE`、`TRUNCATE`、不带 `WHERE` 的 `DELETE`。真要做结构性删除，单独写脚本并人工确认，不要放进自动执行目录。

## 六、回滚

代码层面回滚（本次及以后都适用，因为只新增表，旧代码不认识新表也无害）：

```bash
cd /opt/mattTest && git checkout <更新前的SHA>
```

SHA 在 `update.sh` 输出的「更新前提交」里，也在日志 `/opt/logs/update-*.log` 里。

```bash
cd /opt/mattTest && docker compose -f docker-compose.lite.yml up -d --build app
```

预期：重新构建旧版并启动，健康检查恢复。

回滚后 `HEAD` 是游离状态，下次正常发版前要切回分支：

```bash
cd /opt/mattTest && git checkout main
```

数据回滚**只在确认要丢掉这段时间的数据时**才做：

```bash
cd /opt/mattTest && docker compose -f docker-compose.lite.yml exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" supply_chain' < /opt/backups/supply_chain-2026-11-22-231500.sql
```

预期：无输出。执行前先想清楚。

## 七、排错

| 现象 | 原因与处理 |
|---|---|
| `deploy.sh` 报 `缺少 docker compose 插件` | 装的是老版 `docker-compose`。装 `docker compose` v2 插件 |
| `compose 校验失败` | `.env` 缺必需键，脚本会列出缺哪几个 |
| `Update scripts not found` / `No such file` | 还没 `git pull`，或拉的分支里没有 `ops/`。确认 `ls /opt/mattTest/ops` |
| `拉取失败：服务器上有未提交改动` | 服务器上有人改过文件。`cd /opt/mattTest && git status` 查，`git checkout -- .` 丢弃后重跑 |
| `fast-forward 失败` | 服务器与远程分叉了。**不要强推**，先查服务器多了什么提交 |
| 迁移失败 | 修好 SQL 后直接重跑 `update.sh`，已成功的迁移会被跳过 |
| 健康检查超时 | 看 `docker compose -f docker-compose.lite.yml logs --tail 120 app`，常见是库连接失败或迁移没做 |
| `/shop` 返回 401 | 镜像没换成功：`docker compose ps` 看 app 的启动时间是否是刚刚 |
| 备份文件是 0 字节 | `MYSQL_ROOT_PASSWORD` 与容器不一致，检查 `.env` |
| `bash: $'\r': command not found` | 脚本被 Windows 换行污染。`sed -i 's/\r$//' /opt/mattTest/ops/*.sh` |

## 八、修改规则（重要）

`backend/ops/` 下的 `deploy.sh`、`update.sh`、`docker-compose.lite.yml`、`.env.example` 是**冻结资产**。

- 只有用户明确说「更新部署脚本」或「更新更新脚本」时，才允许修改这四个文件。
- 实现业务功能、修 bug、调接口时，**不得**顺手改它们，也不得为了"顺手方便"加参数。
- 改之前必须说明「为什么现有脚本不够用」，改完必须在服务器上用 `--dry-run` 验证一次。
- 新增迁移 SQL **不算**修改脚本，按第五节要求正常添加即可。

理由：这两个脚本直接操作线上数据和容器，改错一次的代价是停机加数据风险，比业务代码高一个量级。

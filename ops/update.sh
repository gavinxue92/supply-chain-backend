#!/usr/bin/env bash
# ============================================================
# 增量更新脚本 —— 日常发版用（线上已有数据，不能重建库）
# 作用：备份 -> fast-forward 拉代码 -> 执行未应用的迁移 -> 重建 app -> 健康检查 -> 验收
# 不做：不执行 schema.sql、不删数据、不 reset --hard、不动 mysql/minio 容器
# 用法：bash update.sh
#       bash update.sh --dry-run          只看计划，不执行
#       bash update.sh --no-backup        跳过备份（不推荐）
#       bash update.sh --no-build         只拉代码与迁移，不重建镜像
# ============================================================
set -euo pipefail

APP="${APP_DIR:-/opt/mattTest}"
BRANCH="${BRANCH:-main}"
CF="docker-compose.lite.yml"
BACKUP_DIR="${BACKUP_DIR:-/opt/backups}"
KEEP="${KEEP:-10}"
LOG_DIR="${LOG_DIR:-/opt/logs}"
DO_BACKUP=1
DO_BUILD=1
DRY_RUN=0

while [ $# -gt 0 ]; do
  case "$1" in
    --app)       APP="$2"; shift 2 ;;
    --branch)    BRANCH="$2"; shift 2 ;;
    --no-backup) DO_BACKUP=0; shift ;;
    --no-build)  DO_BUILD=0; shift ;;
    --dry-run)   DRY_RUN=1; shift ;;
    -h|--help)   sed -n '2,10p' "$0"; exit 0 ;;
    *)           echo "未知参数：$1（用 --help 查看）" >&2; exit 1 ;;
  esac
done

mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/update-$(date +%F-%H%M%S).log"
exec > >(tee -a "$LOG") 2>&1

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[32m  [OK] %s\033[0m\n' "$*"; }
warn() { printf '\033[33m  [!!] %s\033[0m\n' "$*"; }
die()  { printf '\033[31m  [XX] %s\033[0m\n' "$*"; exit 1; }
run()  { if [ "$DRY_RUN" = 1 ]; then printf '  [dry-run] %s\n' "$*"; else "$@"; fi; }

dc()       { docker compose -f "$CF" "$@"; }
mysql_in() { dc exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" supply_chain'; }

step "0/9 环境检查"
[ -d "$APP/.git" ] || die "$APP 不是 git 仓库，首次部署请用 deploy.sh"
cd "$APP"
[ -f "$CF" ] || die "$APP 下缺少 $CF"
[ -f .env ]  || die "$APP 下缺少 .env"
for c in docker git curl; do command -v "$c" >/dev/null 2>&1 || die "缺少 $c"; done
dc ps --status running --services 2>/dev/null | grep -qx mysql || die "mysql 容器未运行，先 docker compose -f $CF up -d mysql"
ok "环境就绪（$APP）"

step "1/9 检查工作区是否干净"
DIRTY="$(git status --porcelain --untracked-files=no)"
if [ -n "$DIRTY" ]; then
  printf '%s\n' "$DIRTY"
  die "服务器上有未提交的已跟踪文件改动，fast-forward 会冲突。先 git stash 或 git checkout -- . 再跑"
fi
ok "工作区干净（.env 与编排文件不在版本控制内，不受影响）"

OLD_SHA="$(git rev-parse --short HEAD)"
OLD_MSG="$(git log --oneline -1)"
ok "更新前：$OLD_MSG"

step "2/9 备份数据库"
if [ "$DO_BACKUP" = 1 ]; then
  mkdir -p "$BACKUP_DIR"
  BACKUP="$BACKUP_DIR/supply_chain-$(date +%F-%H%M%S).sql"
  if [ "$DRY_RUN" = 1 ]; then
    printf '  [dry-run] mysqldump -> %s\n' "$BACKUP"
  else
    dc exec -T mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers supply_chain' > "$BACKUP"
    [ -s "$BACKUP" ] || die "备份文件为空：$BACKUP"
    ok "备份 $(du -h "$BACKUP" | cut -f1) -> $BACKUP"
    ls -1t "$BACKUP_DIR"/supply_chain-*.sql 2>/dev/null | tail -n "+$((KEEP+1))" | xargs -r rm -f
    ok "已保留最近 $KEEP 份备份"
  fi
else
  warn "已跳过备份（--no-backup）"
fi

step "3/9 拉取代码（只允许 fast-forward）"
run git fetch origin "$BRANCH"
if [ "$DRY_RUN" = 0 ]; then
  git pull --ff-only origin "$BRANCH" || die "fast-forward 失败，已停止（数据库与镜像均未改动）"
  NEW_SHA="$(git rev-parse --short HEAD)"
  if [ "$OLD_SHA" = "$NEW_SHA" ]; then
    warn "代码无变化（$NEW_SHA）"
  else
    ok "代码更新：$OLD_SHA -> $NEW_SHA"
    git log --oneline "$OLD_SHA..$NEW_SHA" | sed 's/^/     /'
  fi
else
  printf '  [dry-run] git pull --ff-only origin %s\n' "$BRANCH"
fi

step "4/9 准备迁移记录表"
if [ "$DRY_RUN" = 1 ]; then
  printf '  [dry-run] CREATE TABLE IF NOT EXISTS schema_migrations\n'
else
  mysql_in >/dev/null <<'SQL'
CREATE TABLE IF NOT EXISTS schema_migrations (
    filename   VARCHAR(200) NOT NULL COMMENT '迁移脚本文件名；相对 scripts/migrations/',
    applied_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '执行时间',
    PRIMARY KEY (filename)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='运维元数据：记录已执行的增量迁移，防止重复运行';
SQL
  ok "schema_migrations 就绪"
fi

is_applied() {
  local f="$1" c
  c="$(echo "SELECT COUNT(*) FROM schema_migrations WHERE filename='$f';" \
        | dc exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B supply_chain' 2>/dev/null | tr -d '\r')"
  [ "${c:-0}" -gt 0 ]
}

step "5/9 执行未应用的迁移"
shopt -s nullglob
FILES=(scripts/migrations/*.sql)
shopt -u nullglob
if [ "${#FILES[@]}" -eq 0 ]; then
  ok "没有迁移脚本"
else
  for f in "${FILES[@]}"; do
    b="$(basename "$f")"
    if [ "$DRY_RUN" = 0 ] && is_applied "$b"; then
      ok "跳过（已执行）：$b"
      continue
    fi
    printf '  执行：%s\n' "$b"
    if [ "$DRY_RUN" = 1 ]; then
      printf '  [dry-run] mysql < %s\n' "$f"
      continue
    fi
    mysql_in < "$f" || die "迁移失败：$b —— 修复后重跑本脚本（脚本要求幂等，已成功的部分可重复执行）"
    echo "INSERT INTO schema_migrations (filename) VALUES ('$b') ON DUPLICATE KEY UPDATE filename=filename;" | mysql_in >/dev/null
    ok "完成并已记录：$b"
  done
fi

step "6/9 重建应用镜像（只重建 app，不动 mysql/minio）"
if [ "$DO_BUILD" = 1 ]; then
  if [ "$DRY_RUN" = 1 ]; then
    printf '  [dry-run] docker compose -f %s up -d --build app\n' "$CF"
  else
    dc up -d --build app
    ok "app 已替换"
  fi
else
  warn "已跳过构建（--no-build），代码已更新但未生效"
fi

step "7/9 健康检查（最长 10 分钟）"
if [ "$DRY_RUN" = 1 ]; then
  printf '  [dry-run] 等待 /api/v1/health 返回 200\n'
else
  WEB_OK=0
  for _ in $(seq 1 60); do
    if curl -fsS -m 3 http://127.0.0.1:8080/api/v1/health >/dev/null 2>&1; then WEB_OK=1; break; fi
    sleep 10
  done
  if [ "$WEB_OK" != 1 ]; then
    dc logs --tail 120 app
    die "健康检查超时。回滚见下方提示"
  fi
  ok "健康：$(curl -s -m 5 http://127.0.0.1:8080/api/v1/health)"
fi

step "8/9 接口验收"
check() {
  local path="$1" name="$2" out code
  out="$(curl -s -m 8 -w '\n%{http_code}' "http://127.0.0.1:8080$path" || true)"
  code="$(printf '%s' "$out" | tail -n1)"
  if [ "$code" = "200" ]; then
    ok "$name  ->  200"
  else
    warn "$name  ->  $code  （$path）"
  fi
}
if [ "$DRY_RUN" = 0 ]; then
  check "/api/v1/health" "健康检查"
  check "/api/v1/categories" "分类树"
  check "/api/v1/shop" "店铺信息"
  check "/api/v1/search/hot-keywords" "搜索热词"
  check "/api/v1/products?page=1&pageSize=2" "产品分页"
  PATHS="$(curl -s -m 10 http://127.0.0.1:8080/api/v1/openapi 2>/dev/null | grep -o '"/api/v1[^"]*"' | sort -u | wc -l || true)"
  printf '  当前 OpenAPI 路径数：%s（部署前基线可与本机 docs/openapi.json 对比）\n' "${PATHS:-未知}"
else
  printf '  [dry-run] 跳过接口验收\n'
fi

step "9/9 完成"
if [ "$DRY_RUN" = 0 ]; then
  dc ps
fi
printf '\n  更新前提交 : %s\n  更新后提交 : %s\n  日志文件   : %s\n' \
  "$OLD_SHA" "$(git rev-parse --short HEAD)" "$LOG"
printf '\n  回滚命令（代码层面，数据库无需回滚）：\n'
printf '    cd %s && git checkout %s && docker compose -f %s up -d --build app\n' "$APP" "$OLD_SHA" "$CF"
printf '\n  数据回滚（仅在确认要丢数据时）：\n'
printf '    cd %s && docker compose -f %s exec -T mysql sh -c '"'"'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" supply_chain'"'"' < %s/supply_chain-XXXX.sql\n\n' \
  "$APP" "$CF" "$BACKUP_DIR"

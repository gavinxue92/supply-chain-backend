#!/usr/bin/env bash
# ============================================================
# 全量部署脚本 —— 用于全新服务器 / 灾难恢复
# 作用：拉代码 -> 准备 .env 与编排 -> 构建启动 -> 校验表 -> 建管理员 -> 健康检查
# 不作用：不动已有数据卷、不删数据库、不改业务代码
# 用法：bash deploy.sh
#       bash deploy.sh --app /opt/mattTest --branch main --force-compose
# ============================================================
set -euo pipefail

APP="${APP_DIR:-/opt/mattTest}"
REPO="${REPO_URL:-https://github.com/gavinxue92/supply-chain-backend.git}"
BRANCH="${BRANCH:-main}"
CF="docker-compose.lite.yml"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="${LOG_DIR:-/opt/logs}"
FORCE_COMPOSE=0

while [ $# -gt 0 ]; do
  case "$1" in
    --app)           APP="$2"; shift 2 ;;
    --repo)          REPO="$2"; shift 2 ;;
    --branch)        BRANCH="$2"; shift 2 ;;
    --force-compose) FORCE_COMPOSE=1; shift ;;
    -h|--help)       sed -n '2,8p' "$0"; exit 0 ;;
    *)               echo "未知参数：$1（用 --help 查看）" >&2; exit 1 ;;
  esac
done

mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/deploy-$(date +%F-%H%M%S).log"
exec > >(tee -a "$LOG") 2>&1

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[32m  [OK] %s\033[0m\n' "$*"; }
warn() { printf '\033[33m  [!!] %s\033[0m\n' "$*"; }
die()  { printf '\033[31m  [XX] %s\033[0m\n' "$*"; exit 1; }

dc()       { docker compose -f "$CF" "$@"; }
mysql_in() { dc exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" supply_chain'; }
count_tables() {
  echo "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='supply_chain';" \
    | dc exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B' 2>/dev/null | tr -d '\r'
}
gen_pass() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 16
  else
    (head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c1-32) || true
  fi
}

step "0/8 环境检查"
for c in docker git curl; do
  command -v "$c" >/dev/null 2>&1 || die "缺少 $c，请先安装"
done
docker compose version >/dev/null 2>&1 || die "缺少 docker compose 插件（不是 docker-compose 老版本）"
[ "$(id -u)" = "0" ] || warn "当前不是 root，docker 可能需要 sudo"
ok "docker $(docker --version | awk '{print $3}' | tr -d ,)"

step "1/8 获取代码到 $APP"
if [ -d "$APP/.git" ]; then
  cd "$APP"
  git fetch origin "$BRANCH"
  git checkout "$BRANCH" >/dev/null 2>&1 || git checkout -b "$BRANCH" "origin/$BRANCH"
  git pull --ff-only origin "$BRANCH" || die "拉取失败：服务器上有未提交改动。先 git status 查看并处理"
else
  mkdir -p "$(dirname "$APP")"
  [ -e "$APP" ] && rm -rf "$APP"
  git clone --branch "$BRANCH" "$REPO" "$APP"
  cd "$APP"
fi
[ -f Dockerfile ] || die "$APP 下没有 Dockerfile，仓库或分支不对"
ok "当前提交 $(git log --oneline -1)"

step "2/8 准备 .env"
if [ -f .env ]; then
  MISS=""
  for k in SPRING_DATASOURCE_PASSWORD MYSQL_ROOT_PASSWORD MINIO_ROOT_USER \
           MINIO_ROOT_PASSWORD APP_MEDIA_STORAGE_PUBLIC_ENDPOINT APP_MEDIA_PUBLIC_BASE_URL; do
    grep -q "^${k}=" .env || MISS="$MISS $k"
  done
  [ -z "$MISS" ] || die ".env 缺少必需键：$MISS"
  chmod 600 .env
  ok ".env 已存在，必需键齐全"
else
  PUB_IP="$(curl -s -m 5 https://ifconfig.me 2>/dev/null || true)"
  [ -n "$PUB_IP" ] || PUB_IP="127.0.0.1"
  DB_PASS="$(gen_pass)"; ROOT_PASS="$(gen_pass)"
  MINIO_USER="scadmin"; MINIO_PASS="$(gen_pass)"
  cat > .env <<ENVEOF
SPRING_DATASOURCE_PASSWORD=$DB_PASS
MYSQL_ROOT_PASSWORD=$ROOT_PASS
MINIO_ROOT_USER=$MINIO_USER
MINIO_ROOT_PASSWORD=$MINIO_PASS
APP_MEDIA_STORAGE_BUCKET=product-media
APP_MEDIA_STORAGE_PUBLIC_ENDPOINT=http://$PUB_IP:9000
APP_MEDIA_PUBLIC_BASE_URL=http://$PUB_IP:8080
APP_ALLOWED_ORIGINS=http://localhost:5173
APP_COOKIE_SECURE=false
SWAGGER_UI_ENABLED=false
ENVEOF
  chmod 600 .env
  warn "已生成 .env（随机密码，公网 IP 推定 $PUB_IP）"
  warn "立即备份此文件：$APP/.env —— 丢了连不上库"
fi

step "3/8 准备编排文件"
if [ -f "$CF" ] && [ "$FORCE_COMPOSE" = 0 ]; then
  ok "$CF 已存在（要覆盖用 --force-compose）"
else
  [ -f "$HERE/$CF" ] || die "脚本目录缺少 $CF，请把编排文件与本脚本放在同一目录"
  cp -f "$HERE/$CF" "$CF"
  ok "已复制 $CF"
fi
dc config --quiet || die "compose 校验失败（多为 .env 变量缺失）"
ok "compose 校验通过"

step "4/8 构建并启动（首次 10-20 分钟，2 核机器慢是正常的，别中断）"
dc up -d --build
ok "容器已创建"

step "5/8 等待 MySQL 就绪"
MYSQL_OK=0
for _ in $(seq 1 60); do
  if dc exec -T mysql sh -c 'mysqladmin ping -h127.0.0.1 -uroot -p"$MYSQL_ROOT_PASSWORD" --silent' >/dev/null 2>&1; then
    MYSQL_OK=1; break
  fi
  sleep 3
done
[ "$MYSQL_OK" = 1 ] || { dc logs --tail 100 mysql; die "MySQL 未就绪"; }
ok "MySQL 就绪"

step "6/8 校验表结构"
T="$(count_tables)"; T="${T:-0}"
if [ "$T" -eq 0 ]; then
  warn "数据库为空，执行 scripts/schema.sql 初始化"
  mysql_in < scripts/schema.sql || die "schema.sql 执行失败"
  T="$(count_tables)"; T="${T:-0}"
fi
[ "$T" -ge 15 ] || die "表数只有 $T（预期 >=15），检查 scripts/schema.sql 是否完整"
ok "表数 $T"

step "7/8 初始化管理员账号"
if [ -f scripts/init-local-admin.sql ]; then
  if mysql_in < scripts/init-local-admin.sql 2>/dev/null; then
    ok "管理员已写入：admin / Admin123!（首次登录强制改密）"
  else
    warn "管理员已存在或写入失败，不影响启动"
  fi
else
  warn "缺少 scripts/init-local-admin.sql，跳过"
fi

step "8/8 健康检查（最长 10 分钟）"
WEB_OK=0
for _ in $(seq 1 60); do
  if curl -fsS -m 3 http://127.0.0.1:8080/api/v1/health >/dev/null 2>&1; then
    WEB_OK=1; break
  fi
  sleep 10
done
if [ "$WEB_OK" != 1 ]; then
  dc logs --tail 120 app
  die "健康检查超时，日志见上"
fi
ok "后端健康：$(curl -s -m 5 http://127.0.0.1:8080/api/v1/health)"

step "部署完成"
dc ps
PUB="$(grep -E '^APP_MEDIA_PUBLIC_BASE_URL=' .env | cut -d= -f2-)"
printf '\n  访问地址 : %s/api/v1/health\n  日志文件 : %s\n  配置备份 : %s/.env\n\n' \
  "${PUB:-http://127.0.0.1:8080}" "$LOG" "$APP"

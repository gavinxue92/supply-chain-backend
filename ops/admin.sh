#!/usr/bin/env bash
# ============================================================
# 管理端更新脚本 —— 在服务器上构建 Vue 并发布静态文件
# 作用：Node 检查 -> 同步代码 -> 装依赖 -> 构建 -> 发布到 nginx 目录 -> 自检
# 不做：不重启后端、不改 .env、不改 nginx 配置（除非 --init-nginx）
# 用法：bash admin.sh
#       bash admin.sh --dry-run        只看计划
#       bash admin.sh --detach         后台跑（构建 5-15 分钟，防终端断线）
#       bash admin.sh --init-nginx     首次部署时写 nginx 站点配置
# ============================================================
set -euo pipefail

ADMIN="${ADMIN_DIR:-/opt/supply-chain-admin}"
DIST="${DIST_DIR:-/opt/admin-dist}"
REPO="${REPO_URL:-https://github.com/gavinxue92/supply-chain-admin.git}"
BRANCH="${BRANCH:-main}"
NODE_DIR="${NODE_DIR:-/opt/node}"
BACKUP_KEEP="${BACKUP_KEEP:-2}"
LOG_DIR="${LOG_DIR:-/opt/logs}"
DRY_RUN=0
DO_BUILD=1
DO_NGINX=0
DETACH=0
ORIG_ARGS=("$@")

while [ $# -gt 0 ]; do
  case "$1" in
    --admin-dir)  ADMIN="$2"; shift 2 ;;
    --dist)       DIST="$2"; shift 2 ;;
    --repo)       REPO="$2"; shift 2 ;;
    --branch)     BRANCH="$2"; shift 2 ;;
    --no-build)   DO_BUILD=0; shift ;;
    --init-nginx) DO_NGINX=1; shift ;;
    --detach)     DETACH=1; shift ;;
    --dry-run)    DRY_RUN=1; shift ;;
    -h|--help)    sed -n '2,10p' "$0"; exit 0 ;;
    *)            echo "未知参数：$1（用 --help 查看）" >&2; exit 1 ;;
  esac
done

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[32m  [OK] %s\033[0m\n' "$*"; }
warn() { printf '\033[33m  [!!] %s\033[0m\n' "$*"; }
die()  { printf '\033[31m  [XX] %s\033[0m\n' "$*"; exit 1; }

case "$DIST" in
  ""|"/"|"/opt"|"/opt/") die "DIST 路径不安全：'$DIST'，拒绝继续" ;;
esac
case "$ADMIN" in
  ""|"/"|"/opt"|"/opt/") die "ADMIN 路径不安全：'$ADMIN'，拒绝继续" ;;
esac

if [ "$DETACH" = 1 ] && [ "${SC_DETACHED:-0}" != "1" ]; then
  mkdir -p "$LOG_DIR"
  L="$LOG_DIR/admin-$(date +%F-%H%M%S).log"
  if [ "${#ORIG_ARGS[@]}" -gt 0 ]; then
    LOG_FILE="$L" SC_DETACHED=1 setsid nohup bash "$0" "${ORIG_ARGS[@]}" >"$L" 2>&1 </dev/null &
  else
    LOG_FILE="$L" SC_DETACHED=1 setsid nohup bash "$0" >"$L" 2>&1 </dev/null &
  fi
  printf '已在后台启动（PID %s）\n日志：%s\n查看进度：tail -f %s\n' "$!" "$L" "$L"
  exit 0
fi

if [ "${SC_DETACHED:-0}" = "1" ]; then
  LOG="${LOG_FILE:-$LOG_DIR/admin-detached.log}"
else
  mkdir -p "$LOG_DIR"
  LOG="$LOG_DIR/admin-$(date +%F-%H%M%S).log"
  exec > >(tee -a "$LOG") 2>&1
fi

step "0/7 环境检查"
for c in git curl; do command -v "$c" >/dev/null 2>&1 || die "缺少 $c"; done
[ "$(id -u)" = "0" ] || warn "当前不是 root，装 Node 与写 /opt 可能失败"
command -v nginx >/dev/null 2>&1 || warn "未安装 nginx，静态文件发布后无法访问"
ok "基础命令就绪"

step "1/7 检查 Node（需要 >=20.19，系统自带 18 不够）"
export PATH="$NODE_DIR/bin:$PATH"
NODE_OK=0
if command -v node >/dev/null 2>&1; then
  V="$(node -v | sed 's/^v//;s/\..*//')"
  [ "${V:-0}" -ge 20 ] && NODE_OK=1
fi
if [ "$NODE_OK" = 1 ]; then
  ok "node $(node -v) / npm $(npm -v)"
else
  if [ "$DRY_RUN" = 1 ]; then
    printf '  [dry-run] 下载 Node 22 到 %s\n' "$NODE_DIR"
  else
    warn "Node 版本不足，从 npmmirror 下载 Node 22"
    mkdir -p "$NODE_DIR"
    GOT=0
    for v in v22.14.0 v22.13.0 v22.12.0; do
      url="https://registry.npmmirror.com/-/binary/node/$v/node-$v-linux-x64.tar.xz"
      printf '  尝试 %s\n' "$v"
      if curl -fL --retry 2 -m 600 "$url" -o /tmp/node.tar.xz; then
        tar -xJf /tmp/node.tar.xz -C "$NODE_DIR" --strip-components=1
        GOT=1; break
      fi
    done
    [ "$GOT" = 1 ] || die "Node 下载失败，检查 registry.npmmirror.com 是否可达"
    export PATH="$NODE_DIR/bin:$PATH"
    node -v
  fi
fi

step "2/7 同步代码（$ADMIN）"
if [ "$DRY_RUN" = 1 ]; then
  printf '  [dry-run] git clone/pull %s -> %s (%s)\n' "$REPO" "$ADMIN" "$BRANCH"
  printf '  [dry-run] 注意：该目录是纯构建副本，reset --hard 会丢弃上面的一切本地改动\n'
else
  if [ -d "$ADMIN/.git" ]; then
    cd "$ADMIN"
    OLD="$(git log --oneline -1)"
    git fetch origin "$BRANCH"
    git checkout "$BRANCH" >/dev/null 2>&1 || git checkout -b "$BRANCH" "origin/$BRANCH"
    git reset --hard "origin/$BRANCH"
    NEW="$(git log --oneline -1)"
    if [ "$OLD" = "$NEW" ]; then
      warn "代码无变化：$NEW"
    else
      ok "代码更新：$OLD -> $NEW"
    fi
  else
    mkdir -p "$(dirname "$ADMIN")"
    [ -e "$ADMIN" ] && rm -rf "$ADMIN"
    git clone --branch "$BRANCH" "$REPO" "$ADMIN"
    cd "$ADMIN"
    ok "已克隆：$(git log --oneline -1)"
  fi
fi

step "3/7 安装依赖"
if [ "$DRY_RUN" = 1 ]; then
  printf '  [dry-run] npm ci（package-lock 未变则跳过）\n'
else
  cd "$ADMIN"
  npm config set registry https://registry.npmmirror.com >/dev/null 2>&1 || true
  HASH=""
  if [ -f package-lock.json ]; then
    HASH="$(md5sum package-lock.json | cut -d' ' -f1)"
  fi
  if [ -n "$HASH" ] && [ -d node_modules ] && [ -f .npm-lock-hash ] && [ "$(cat .npm-lock-hash)" = "$HASH" ]; then
    ok "package-lock 未变化，跳过安装"
  else
    if [ -f package-lock.json ]; then
      npm ci --no-audit --no-fund || npm install --no-audit --no-fund
    else
      npm install --no-audit --no-fund
    fi
    if [ -n "$HASH" ]; then printf '%s\n' "$HASH" > .npm-lock-hash; fi
    ok "依赖安装完成"
  fi
fi

step "4/7 构建（5-15 分钟，2 核机器正常慢）"
if [ "$DO_BUILD" = 1 ]; then
  if [ "$DRY_RUN" = 1 ]; then
    printf '  [dry-run] npm run build（失败则降级 npx vite build）\n'
  else
    cd "$ADMIN"
    export NODE_OPTIONS=--max-old-space-size=1536
    if npm run build; then
      ok "构建通过（含类型检查）"
    else
      warn "完整构建失败（多为 vue-tsc 类型错误），降级为 vite build，跳过类型检查"
      npx vite build || die "构建失败，未发布，线上仍是旧版本"
    fi
    [ -f dist/index.html ] || die "产物缺失：$ADMIN/dist/index.html"
    ok "产物 $(du -sh dist | cut -f1)"
  fi
else
  warn "已跳过构建（--no-build），将发布现有 dist"
fi

step "5/7 发布到 $DIST"
if [ "$DRY_RUN" = 1 ]; then
  printf '  [dry-run] 备份旧 %s -> %s.bak-<ts>（保留 %s 份）\n' "$DIST" "$DIST" "$BACKUP_KEEP"
  printf '  [dry-run] 清空 %s 后复制 %s/dist/.\n' "$DIST" "$ADMIN"
else
  if [ -d "$DIST" ] && [ -n "$(ls -A "$DIST" 2>/dev/null || true)" ]; then
    B="$DIST.bak-$(date +%F-%H%M%S)"
    cp -a "$DIST" "$B"
    ok "旧版本备份 -> $B"
    ls -1dt "$DIST".bak-* 2>/dev/null | tail -n "+$((BACKUP_KEEP+1))" | xargs -r rm -rf
  fi
  [ -d "$ADMIN/dist" ] || die "没有可发布的 dist，先去掉 --no-build 跑一次"
  mkdir -p "$DIST"
  find "$DIST" -mindepth 1 -delete
  cp -a "$ADMIN/dist/." "$DIST/"
  ok "已发布 $(ls -1 "$DIST/assets" 2>/dev/null | wc -l) 个 assets 文件"
fi

step "6/7 nginx"
if [ "$DO_NGINX" = 1 ]; then
  if [ "$DRY_RUN" = 1 ]; then
    printf '  [dry-run] 写入 /etc/nginx/sites-available/admin 并 reload\n'
  else
    cat > /etc/nginx/sites-available/admin <<NGXEOF
server {
    listen 80 default_server;
    server_name _;

    root $DIST;
    index index.html;
    client_max_body_size 20m;

    gzip on;
    gzip_min_length 1024;
    gzip_types text/css application/javascript application/json image/svg+xml;

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 60s;
    }

    location / {
        try_files \$uri \$uri/ /index.html;
    }
}
NGXEOF
    rm -f /etc/nginx/sites-enabled/default
    ln -sf /etc/nginx/sites-available/admin /etc/nginx/sites-enabled/admin
    nginx -t || die "nginx 配置校验失败，未 reload，线上仍是旧配置"
    systemctl reload nginx || systemctl restart nginx
    ok "nginx 已生效（root=$DIST）"
  fi
else
  if [ "$DRY_RUN" = 1 ]; then
    printf '  [dry-run] 不改 nginx 配置，仅确认 root 指向 %s\n' "$DIST"
  else
    if nginx -T 2>/dev/null | grep -q "root $DIST;"; then
      ok "nginx root 已指向 $DIST（未改动配置）"
    else
      warn "nginx 配置里没找到 'root $DIST;'，站点可能仍指向旧目录。确认后用 --init-nginx 重写"
    fi
  fi
fi

step "7/7 自检"
if [ "$DRY_RUN" = 1 ]; then
  printf '  [dry-run] 检查首页 200 与 /api 反代 200\n'
else
  HOMECODE="$(curl -s -o /dev/null -w '%{http_code}' -m 8 http://127.0.0.1/ || true)"
  APICODE="$(curl -s -o /dev/null -w '%{http_code}' -m 8 http://127.0.0.1/api/v1/health || true)"
  [ "$HOMECODE" = "200" ] && ok "管理端首页 -> 200" || warn "管理端首页 -> $HOMECODE"
  [ "$APICODE" = "200" ] && ok "nginx 反代 /api -> 200" || warn "nginx 反代 /api -> $APICODE（检查后端是否在 8080）"
  printf '  当前入口：'
  grep -o 'assets/index-[^"]*\.js' "$DIST/index.html" 2>/dev/null | head -1 || echo "读取失败"
fi

step "完成"
printf '\n  管理端地址 : http://<服务器IP>/\n  代码目录   : %s\n  发布目录   : %s\n  日志文件   : %s\n' "$ADMIN" "$DIST" "$LOG"
printf '\n  浏览器请按 Ctrl+Shift+R 强制刷新，否则可能加载缓存的旧 index.html\n'
printf '  回滚：把 %s.bak-<时间戳> 改名回 %s 即可\n\n' "$DIST" "$DIST"

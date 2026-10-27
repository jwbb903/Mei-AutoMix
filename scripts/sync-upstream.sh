#!/usr/bin/env bash
# 把上游 ljyh223/Mei 的新提交同步进本仓库。
#
# 本仓库是「单提交」形态（没有上游历史），直接 `git fetch upstream` 会把
# 200MB+ 的完整历史重新拉一遍，非常慢。所以这里改成：
#   1. 在本地已有的完整克隆（$UPSTREAM_REPO）里 fetch —— 那里是增量，几秒钟；
#   2. 只把「上次同步点 → 上游最新」的 diff 打进本仓库。
#
# 用法：
#   ./scripts/sync-upstream.sh            # 同步并提交（不推送）
#   ./scripts/sync-upstream.sh --push     # 同步后顺手 push
#
# 状态文件 .upstream-sync 记录上次同步到哪个上游提交。
set -euo pipefail

UPSTREAM_REPO="${UPSTREAM_REPO:-/root/disk/gh/Mei}"
UPSTREAM_REMOTE="${UPSTREAM_REMOTE:-origin}"
UPSTREAM_BRANCH="${UPSTREAM_BRANCH:-main}"
STATE_FILE=".upstream-sync"
PUSH=0
[ "${1:-}" = "--push" ] && PUSH=1

cd "$(dirname "$0")/.."

if [ ! -d "$UPSTREAM_REPO/.git" ]; then
  echo "找不到上游完整克隆：$UPSTREAM_REPO" >&2
  echo "请先：git clone --filter=blob:none https://proxy.sixi.cc/https://github.com/ljyh223/Mei.git $UPSTREAM_REPO" >&2
  exit 1
fi

echo "==> 拉取上游增量"
git -C "$UPSTREAM_REPO" fetch --quiet "$UPSTREAM_REMOTE" "$UPSTREAM_BRANCH"
TIP=$(git -C "$UPSTREAM_REPO" rev-parse "$UPSTREAM_REMOTE/$UPSTREAM_BRANCH")

BASE=$(cat "$STATE_FILE" 2>/dev/null || true)
if [ -z "$BASE" ]; then
  # 首次：以本仓库提交里记录的基线为准（脚本第一次跑时取上游 HEAD 作为起点）
  BASE="$TIP"
  echo "$TIP" > "$STATE_FILE"
  echo "==> 首次同步，记录基线 $BASE"
  exit 0
fi

if [ "$BASE" = "$TIP" ]; then
  echo "==> 已是最新（$TIP）"
  exit 0
fi

echo "==> 上游 $(git -C "$UPSTREAM_REPO" rev-list --count "$BASE..$TIP") 个新提交"
git -C "$UPSTREAM_REPO" log --oneline "$BASE..$TIP" | sed 's/^/    /'

PATCH=$(mktemp)
trap 'rm -f "$PATCH"' EXIT
git -C "$UPSTREAM_REPO" diff "$BASE..$TIP" > "$PATCH"

# 我们自己的 CI 工作流与上游的不兼容（上游有 main.yml/beta-build.yml，
# 本仓库换成 release.yml），这几处不跟随。
git apply --reject \
  --exclude='.github/workflows/main.yml' \
  --exclude='.github/workflows/beta-build.yml' \
  "$PATCH" || true

if find . -name '*.rej' | grep -q .; then
  echo "==> 有冲突需要手工处理：" >&2
  find . -name '*.rej' | sed 's/^/    /' >&2
  exit 2
fi

echo "$TIP" > "$STATE_FILE"
git add -A
git commit -q -m "chore: follow upstream ljyh223/Mei $(printf '%.7s' "$TIP")"
echo "==> 已提交 $(printf '%.7s' "$TIP")"

if [ "$PUSH" = "1" ]; then
  git push origin main
fi

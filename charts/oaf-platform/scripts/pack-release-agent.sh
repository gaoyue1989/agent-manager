#!/usr/bin/env bash
# 构建期：把仓库 release-agent/ 源打成 OAF 包 zip（chart 内置资源）。
# 约定：AGENTS.md 必须位于 zip 根（backend InspectZip 以 zf.Name == "AGENTS.md" 定位包清单）。
# 用法：charts/oaf-platform/scripts/pack-release-agent.sh（chart 打包流水线在 helm package 前调用）
set -euo pipefail
CHART_DIR=$(cd "$(dirname "$0")/.." && pwd)
REPO_ROOT=$(cd "$CHART_DIR/../.." && pwd)
SRC="$REPO_ROOT/release-agent"
OUT="$CHART_DIR/resources/release-agent.oaf.zip"

[ -f "$SRC/AGENTS.md" ] || { echo "缺少 $SRC/AGENTS.md"; exit 1; }
command -v zip >/dev/null || { echo "需要 zip 命令"; exit 1; }

mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"
(cd "$SRC" && zip -qr "$OUT" . -x '.git*' '*.DS_Store' '*/.git*')
echo "[pack] $OUT ($(du -h "$OUT" | cut -f1))"
unzip -l "$OUT" | head -8

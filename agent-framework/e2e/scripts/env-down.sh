#!/usr/bin/env bash
# 反向清理：按 .runtime/*.pid 终止进程 → 拆 LB → 尽力清理。总是成功（幂等）。
set +e
cd "$(dirname "$0")/.."
RUNTIME="$(pwd)/.runtime"

# sandbox 组：先清 mock 沙箱目录语义由 mock 自理（本地目录直接删）
if [ -f "$RUNTIME/mock-sandbox.pid" ]; then
  kill "$(cat "$RUNTIME/mock-sandbox.pid")" 2>/dev/null
  rm -f "$RUNTIME/mock-sandbox.pid"
fi
for f in "$RUNTIME"/agent-*.pid "$RUNTIME"/mock-*.pid "$RUNTIME"/approval-fwd.pid; do
  [ -f "$f" ] || continue
  kill "$(cat "$f")" 2>/dev/null
  rm -f "$f"
done
docker rm -f e2e-lb >/dev/null 2>&1
sleep 1
echo "[env-down] 清理完成"
exit 0

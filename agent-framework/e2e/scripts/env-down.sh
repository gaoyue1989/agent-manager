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
# LB 容器反清理：multi 组 e2e-lb（8100）+ protocol-multi 组 e2e-leadlb/e2e-memberlb
# （8100/8101，修 #74：漏清单导致本地复跑其他组撞 port already allocated）
docker rm -f e2e-lb e2e-leadlb e2e-memberlb >/dev/null 2>&1
sleep 1
echo "[env-down] 清理完成"
exit 0

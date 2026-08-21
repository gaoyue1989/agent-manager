#!/usr/bin/env bash
# 审批 Demo 一键启动：mock MCP (:8813) + 静态/代理服务 (:8913)
# 前置条件：agent-framework 已运行在 :8100（AGENT_CONFIG_DIR 指向本示例 agent-config 目录），
#           且代理访问 :8100 的 LLM/DB 配置已就绪。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
MCP_PORT="${MCP_PORT:-8813}"
UI_PORT="${UI_PORT:-8913}"
BACKEND="${BACKEND:-http://127.0.0.1:8100}"

# 1. 启动 mock MCP 服务
python3 "$HERE/mock-mcp/approval_mcp.py" > /tmp/approval-mcp.log 2>&1 &
MCP_PID=$!
echo "mock-mcp   : http://127.0.0.1:${MCP_PORT} (pid $MCP_PID, log /tmp/approval-mcp.log)"

# 2. 启动静态 + 代理服务
python3 "$HERE/proxy.py" --port "$UI_PORT" --backend "${BACKEND#http://}" > /tmp/approval-ui.log 2>&1 &
UI_PID=$!
echo "demo ui    : http://localhost:${UI_PORT} (pid $UI_PID, log /tmp/approval-ui.log)"

trap 'kill $MCP_PID $UI_PID 2>/dev/null || true' EXIT

# 3. 健康检查提示
sleep 1
if curl -sf http://127.0.0.1:8100/health > /dev/null 2>&1; then
  echo "backend    : OK (agent-framework :8100 在线)"
else
  echo "WARNING: agent-framework :8100 未响应。请先用 AGENT_CONFIG_DIR=$HERE/agent-config 启动它，"
  echo "        示例命令见 README.md「启动 agent-framework」章节。"
fi

echo
echo "打开浏览器访问 http://localhost:${UI_PORT}/ 开始演示"
wait
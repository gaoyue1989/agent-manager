#!/usr/bin/env bash
# e2e 环境编排（e2e-ci-plan §3.4）：CI 与本地同一路径。
# 前置：MySQL/Redis 由外部提供（CI: actions services；本地: scripts/local-infra.sh）。
#
# 用法：E2E_GROUP=core|multi|sandbox ./scripts/env-up.sh
# 产物：.runtime/env.json（端口/pid/日志路径）；各进程日志在 .runtime/logs/
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)

E2E_GROUP="${E2E_GROUP:-core}"
E2E_BASE_PORT="${E2E_BASE_PORT:-8100}"
LLM_MOCK_PORT="${LLM_MOCK_PORT:-18081}"
BENCH_MCP_PORT="${BENCH_MCP_PORT:-18082}"
APPROVAL_MCP_PORT="${APPROVAL_MCP_PORT:-8813}"
SANDBOX_MOCK_PORT="${SANDBOX_MOCK_PORT:-8090}"
MYSQL_URL="${MYSQL_URL:-jdbc:mysql://127.0.0.1:3306/agent_framework_e2e}"
MYSQL_USER="${MYSQL_USER:-e2e}"
MYSQL_PASS="${MYSQL_PASS:-e2e-pass}"
REDIS_URL="${REDIS_URL:-redis://127.0.0.1:6379}"
SANDBOX_ENABLED="${SANDBOX_ENABLED:-false}"
# start-agent.sh（R4 retry 重建副本）复用同组变量，export 透传子进程保持覆盖语义一致
export LLM_MOCK_PORT MYSQL_URL MYSQL_USER MYSQL_PASS REDIS_URL SANDBOX_ENABLED SANDBOX_MOCK_PORT
[ "$E2E_GROUP" = "sandbox" ] && SANDBOX_ENABLED=true

RUNTIME="$ROOT/.runtime"
LOGS="$RUNTIME/logs"
mkdir -p "$LOGS" "$RUNTIME/files" "$RUNTIME/sandboxes"

JAR=$(ls -t "$ROOT"/../target/agent-framework-*.jar 2>/dev/null | head -1)
[ -n "${JAR:-}" ] || { echo "未找到 jar（先 mvn -DskipTests package）"; exit 1; }

# ---------- -1. 数据重置：清空上一轮 E2E 遗留数据（防脏数据污染 ASK/租约语义） ----------
# 用 node 直连（无 mysql/redis-cli 依赖）；表由实例启动时 initSchema 重建
if [ "${E2E_RESET_DATA:-true}" = "true" ]; then
  node "$ROOT/scripts/reset-data.mjs" "$MYSQL_URL" "$MYSQL_USER" "$MYSQL_PASS" "$REDIS_URL" || true
fi

# ---------- 0. 清场：杀掉上一轮残留的同端口进程（陈旧 mock/实例会让新进程绑定失败且难以察觉） ----------
for PORT_CLEAN in "$LLM_MOCK_PORT" "$BENCH_MCP_PORT" "$APPROVAL_MCP_PORT" "$SANDBOX_MOCK_PORT" "$E2E_BASE_PORT" "$((E2E_BASE_PORT + 1))" "$((E2E_BASE_PORT + 2))"; do
  PIDS=$(ss -ltnp 2>/dev/null | grep ":${PORT_CLEAN} " | grep -oP 'pid=\K[0-9]+' | sort -u || true)
  for p in $PIDS; do kill -9 "$p" 2>/dev/null || true; done
done
sleep 1

# ---------- 1. 渲染 agent-config（MCP 地址按实际端口） ----------
AGENT_CFG="$RUNTIME/agent-config"
rm -rf "$AGENT_CFG"
cp -r "$ROOT/fixtures/agent-config" "$AGENT_CFG"
for f in "$AGENT_CFG"/mcp-configs/*/config.yaml; do
  sed -i "s|http://127.0.0.1:8813|http://127.0.0.1:${APPROVAL_MCP_PORT}|g; s|http://172.17.0.1:18082/mcp|http://127.0.0.1:${BENCH_MCP_PORT}/mcp|g; s|http://127.0.0.1:18082/mcp|http://127.0.0.1:${BENCH_MCP_PORT}/mcp|g" "$f"
done

# ---------- 1.5 最小 logback 配置（仅控制台）：应用自带配置强写 /applog，CI 非 root 不可写 ----------
cat > "$AGENT_CFG/logback-e2e.xml" <<'XML'
<configuration>
  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder><pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern></encoder>
  </appender>
  <root level="INFO"><appender-ref ref="CONSOLE"/></root>
</configuration>
XML

# ---------- 2. mock 进程 ----------
MOCK_LLM_PORT="$LLM_MOCK_PORT" node "$ROOT/mock/llm-server.mjs" > "$LOGS/mock-llm.log" 2>&1 &
echo $! > "$RUNTIME/mock-llm.pid"
MOCK_MCP_PORT="$BENCH_MCP_PORT" node "$ROOT/../bench/mock-mcp/server.js" > "$LOGS/mock-bench-mcp.log" 2>&1 &
echo $! > "$RUNTIME/mock-bench-mcp.pid"
python3 "$ROOT/mock/approval-mcp.py" > "$LOGS/mock-approval-mcp.log" 2>&1 &
echo $! > "$RUNTIME/mock-approval-mcp.pid"

"$ROOT/scripts/wait-ready.sh" "http://127.0.0.1:${LLM_MOCK_PORT}/health" 30 mock-llm
"$ROOT/scripts/wait-ready.sh" "http://127.0.0.1:${BENCH_MCP_PORT}/health" 30 mock-bench-mcp
"$ROOT/scripts/wait-ready.sh" "http://127.0.0.1:${APPROVAL_MCP_PORT}/stats" 30 mock-approval-mcp

# approval MCP 地址跟随端口：python 版端口固定 8813，非默认端口经 socat 转发兜底
if [ "$APPROVAL_MCP_PORT" != "8813" ]; then
  nohup python3 -c "
import socket, threading

def pipe(a, b):
    try:
        while True:
            d = a.recv(65536)
            if not d: break
            b.sendall(d)
    except Exception:
        pass
    finally:
        a.close(); b.close()

srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(('127.0.0.1', ${APPROVAL_MCP_PORT})); srv.listen(64)
while True:
    a, _ = srv.accept()
    b = socket.socket(); b.connect(('127.0.0.1', 8813))
    threading.Thread(target=pipe, args=(a, b), daemon=True).start()
    threading.Thread(target=pipe, args=(b, a), daemon=True).start()
" > "$LOGS/approval-port-fwd.log" 2>&1 &
  echo $! > "$RUNTIME/approval-fwd.pid"
  "$ROOT/scripts/wait-ready.sh" "http://127.0.0.1:${APPROVAL_MCP_PORT}/stats" 30 approval-fwd
fi

SANDBOX_PID=""
if [ "$SANDBOX_ENABLED" = "true" ]; then
  MOCK_SANDBOX_PORT="$SANDBOX_MOCK_PORT" MOCK_SANDBOX_ROOT="$RUNTIME/sandboxes" \
    node "$ROOT/mock/sandbox-server.mjs" > "$LOGS/mock-sandbox.log" 2>&1 &
  SANDBOX_PID=$!
  echo "$SANDBOX_PID" > "$RUNTIME/mock-sandbox.pid"
  "$ROOT/scripts/wait-ready.sh" "http://127.0.0.1:${SANDBOX_MOCK_PORT}/health" 30 mock-sandbox
fi

# ---------- 3. 被测实例 ----------
start_jar() { # $1=端口 $2=名字（启动逻辑独立为 scripts/start-agent.sh，R4 retry 重建副本复用同一语义）
  "$ROOT/scripts/start-agent.sh" "$2" "$1"
}

case "$E2E_GROUP" in
  core|sandbox)
    start_jar "$E2E_BASE_PORT" "a"
    ;;
  multi)
    start_jar "$((E2E_BASE_PORT + 1))" "a"
    start_jar "$((E2E_BASE_PORT + 2))" "b"
    # nginx 轮询 LB（刷新续传必须由基础设施真实发生随机路由）
    NGINX_CONF="$RUNTIME/nginx-lb.conf"
    sed "s|8101|$((E2E_BASE_PORT + 1))|g; s|8102|$((E2E_BASE_PORT + 2))|g" "$ROOT/fixtures/nginx-lb.conf.template" > "$NGINX_CONF"
    docker rm -f e2e-lb >/dev/null 2>&1 || true
    docker run -d --name e2e-lb -p "$E2E_BASE_PORT:80" --add-host=host.docker.internal:host-gateway \
      -v "$NGINX_CONF:/etc/nginx/nginx.conf:ro" nginx:alpine > /dev/null
    "$ROOT/scripts/wait-ready.sh" "http://127.0.0.1:${E2E_BASE_PORT}/health" 30 nginx-lb
    ;;
  *) echo "E2E_GROUP 必须是 core|multi|sandbox"; exit 1;;
esac

# ---------- 4. env.json ----------
cat > "$RUNTIME/env.json" <<EOF
{
  "group": "$E2E_GROUP",
  "base": "http://127.0.0.1:${E2E_BASE_PORT}",
  "replicaA": "http://127.0.0.1:$((E2E_BASE_PORT + 1))",
  "replicaB": "http://127.0.0.1:$((E2E_BASE_PORT + 2))",
  "llmMock": "http://127.0.0.1:${LLM_MOCK_PORT}",
  "benchMcp": "http://127.0.0.1:${BENCH_MCP_PORT}",
  "approvalMcp": "http://127.0.0.1:${APPROVAL_MCP_PORT}",
  "sandboxMock": "http://127.0.0.1:${SANDBOX_MOCK_PORT}",
  "sandboxEnabled": $SANDBOX_ENABLED
}
EOF
echo "[env-up] group=$E2E_GROUP base=http://127.0.0.1:${E2E_BASE_PORT} 就绪"

#!/usr/bin/env bash
# 启动单个被测实例（env-up.sh start_jar 的独立化）。
# 除 env-up 首次拉起外，还供 R4 kill 用例 retry 时重建被 SIGKILL 的副本——
# 保证"重试"真正具备自愈能力，而不是死在 stale pid 防护上。
# 用法：./scripts/start-agent.sh <name> <port>
# 前置：env-up.sh 已完成（agent-config 已渲染、mock 进程已就绪）；
#       共享变量（MYSQL_URL/REDIS_URL/LLM_MOCK_PORT 等）与 env-up.sh 同默认值，经环境变量透传覆盖。
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)

NAME="${1:?用法: start-agent.sh <name> <port>}"
PORT="${2:?用法: start-agent.sh <name> <port>}"
LLM_MOCK_PORT="${LLM_MOCK_PORT:-18081}"
MYSQL_URL="${MYSQL_URL:-jdbc:mysql://127.0.0.1:3306/agent_framework_e2e}"
MYSQL_USER="${MYSQL_USER:-e2e}"
MYSQL_PASS="${MYSQL_PASS:-e2e-pass}"
REDIS_URL="${REDIS_URL:-redis://127.0.0.1:6379}"
SANDBOX_ENABLED="${SANDBOX_ENABLED:-false}"
SANDBOX_MOCK_PORT="${SANDBOX_MOCK_PORT:-8090}"

RUNTIME="$ROOT/.runtime"
LOGS="$RUNTIME/logs"
AGENT_CFG="$RUNTIME/agent-config"
JAR=$(ls -t "$ROOT"/../target/agent-framework-*.jar 2>/dev/null | head -1)
[ -n "${JAR:-}" ] || { echo "未找到 jar（先 mvn -DskipTests package）"; exit 1; }
[ -d "$AGENT_CFG" ] || { echo "未找到 $AGENT_CFG（先 env-up.sh 渲染配置）"; exit 1; }
mkdir -p "$LOGS"

# 端口清场（同 env-up 第 0 步语义）：陈旧实例会让新进程绑定失败且难以察觉
PIDS=$(ss -ltnp 2>/dev/null | grep ":${PORT} " | grep -oP 'pid=\K[0-9]+' | sort -u || true)
for p in $PIDS; do kill -9 "$p" 2>/dev/null || true; done
sleep 1

LLM_BASE_URL="http://127.0.0.1:${LLM_MOCK_PORT}/v1" \
LLM_API_KEY="e2e-dummy" LLM_MODEL_ID="e2e-mock-model" \
CHECKPOINT_JDBC_URL="$MYSQL_URL" CHECKPOINT_USERNAME="$MYSQL_USER" CHECKPOINT_PASSWORD="$MYSQL_PASS" \
AGENT_REDIS_URL="$REDIS_URL" \
AGENT_CONFIG_DIR="$AGENT_CFG" \
SERVER_PORT="$PORT" SERVER_HOST="127.0.0.1" \
FILE_STORAGE_TYPE=local FILE_STORAGE_LOCAL_DIR="$RUNTIME/files" \
AGENT_CLEANUP_TURN_LEASE_TTL_SECONDS=15 AGENT_CLEANUP_TURN_LEASE_RENEW_SECONDS=5 \
SANDBOX_ENABLED="$SANDBOX_ENABLED" OPENSANDBOX_SERVER_URL="127.0.0.1:${SANDBOX_MOCK_PORT}" OPENSANDBOX_API_KEY="e2e-placeholder" \
LOGGING_CONFIG="file:$AGENT_CFG/logback-e2e.xml" nohup java -Xms256m -Xmx768m -jar "$JAR" > "$LOGS/agent-$NAME.log" 2>&1 &
echo $! > "$RUNTIME/agent-$NAME.pid"
"$ROOT/scripts/wait-ready.sh" "http://127.0.0.1:${PORT}/health" 90 "agent-$NAME" || {
  echo "[start-agent] agent-$NAME 启动失败，日志尾部：" >&2
  tail -30 "$LOGS/agent-$NAME.log" >&2
  exit 1
}
echo "[start-agent] agent-$NAME(port=$PORT) 就绪"

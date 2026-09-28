#!/usr/bin/env bash
# 启动单个被测实例（env-up.sh start_jar 的独立化）。
# 除 env-up 首次拉起外，还供 R4 kill 用例 retry 时重建被 SIGKILL 的副本——
# 保证"重试"真正具备自愈能力，而不是死在 stale pid 防护上。
# 用法：./scripts/start-agent.sh <name> <port>
# 前置：env-up.sh 已完成（agent-config 已渲染、mock 进程已就绪）；
#       共享变量（MYSQL_URL/REDIS_URL/LLM_MOCK_PORT 等）经环境变量透传覆盖；
#       未注入时回读 .runtime/env.json（env-up 产物，见其 mysql*/redis* 字段），
#       本地两段式运行（env-up 与 playwright 分属两条命令）不再要求手工贯穿环境变量；
#       两者都缺省时才落内置默认值（与 CI services 端口一致）。
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)

NAME="${1:?用法: start-agent.sh <name> <port>}"
PORT="${2:?用法: start-agent.sh <name> <port>}"
LLM_MOCK_PORT="${LLM_MOCK_PORT:-18081}"
SANDBOX_ENABLED="${SANDBOX_ENABLED:-false}"
SANDBOX_MOCK_PORT="${SANDBOX_MOCK_PORT:-8090}"
BENCH_MCP_PORT="${BENCH_MCP_PORT:-18082}"

RUNTIME="$ROOT/.runtime"
LOGS="$RUNTIME/logs"
AGENT_CFG="$RUNTIME/agent-config"

# env.json 回读：只填「环境变量未注入」的键（显式 env 仍最优先）
if [ -f "$RUNTIME/env.json" ] && command -v node >/dev/null 2>&1; then
  jget() { node -e "try{const j=JSON.parse(require('fs').readFileSync(process.argv[2],'utf8'));const v=j[process.argv[1]];if(v!=null&&v!=='')console.log(v)}catch{}" "$1" "$RUNTIME/env.json"; }
  : "${MYSQL_URL:=$(jget mysqlUrl)}"
  : "${MYSQL_USER:=$(jget mysqlUser)}"
  : "${MYSQL_PASS:=$(jget mysqlPass)}"
  : "${REDIS_URL:=$(jget redisUrl)}"
fi
MYSQL_URL="${MYSQL_URL:-jdbc:mysql://127.0.0.1:3306/agent_framework_e2e}"
MYSQL_USER="${MYSQL_USER:-e2e}"
MYSQL_PASS="${MYSQL_PASS:-e2e-pass}"
REDIS_URL="${REDIS_URL:-redis://127.0.0.1:6379}"

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
FILE_EXTERNAL_URL_PREFIXES="http://127.0.0.1:${BENCH_MCP_PORT}" \
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

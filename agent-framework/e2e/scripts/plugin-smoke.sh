#!/usr/bin/env bash
# 工具插件部署冒烟（tool-plugin-extension-plan §4.3 / §7 步骤 4）：
#   1. 现场编译打包示例插件 echo-tool.jar（e2e/plugin-echo/EchoToolPlugin.java）
#   2. 独立进程起服务（本地 MySQL/Redis + mock LLM，与 e2e 环境同源）
#   3. 断言：启动注册日志（Bootstrapper + HarnessAgentFactory）/ /tools?includeInternal 可见 /
#          OAF reload 整包重建后仍注册（工厂日志 + 接口）/ deniedTools 类粒度剔除 / 撤销后恢复
# 前置：mvn -DskipTests package 已产出 target/agent-framework-*.jar；开发 MySQL(3307) 可用；
#       Redis(16379) 未起时自动调 local-infra.sh（e2e-redis，e2e-mysql 顺带拉起但不使用）。
# 用法：./scripts/plugin-smoke.sh   （结束自动清理被测进程与 mock LLM；基础设施容器保留复用）
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)
RT="$ROOT/.runtime-plugin"
PORT="${PLUGIN_SMOKE_PORT:-18110}"
MYSQL_URL="${MYSQL_URL:-jdbc:mysql://127.0.0.1:3307/agent_manager_test}"
MYSQL_USER="${MYSQL_USER:-agent_manager}"
MYSQL_PASS="${MYSQL_PASS:-Agent@Manager2026}"
REDIS_URL="${REDIS_URL:-redis://127.0.0.1:16379}"
LLM_MOCK_PORT="${LLM_MOCK_PORT:-18081}"
LOG="$RT/agent-plugin-smoke.log"
PASS=0; FAIL=0
note() { echo "[plugin-smoke] $*"; }
check() { # check <描述> <命令...>（命令退出码 0 记 PASS）
  local desc="$1"; shift
  if "$@" >/dev/null 2>&1; then PASS=$((PASS+1)); note "PASS  $desc";
  else FAIL=$((FAIL+1)); note "FAIL  $desc"; fi
}
probe() { timeout 1 bash -c "echo -n > /dev/tcp/127.0.0.1/$1" 2>/dev/null; }

# ---------- 0. 基础设施 ----------
if ! probe 3307; then note "开发 MySQL(3307) 未就绪（agent-framework-local-smoke 配方）"; exit 1; fi
if ! probe 16379; then
  note "Redis(16379) 未就绪，调 local-infra.sh"
  "$ROOT/scripts/local-infra.sh" >/dev/null
  for i in $(seq 1 30); do probe 16379 && break; sleep 2; done
fi
probe 16379 || { note "Redis(16379) 未就绪"; exit 1; }

# ---------- 1. 运行时目录 + 插件 jar 现场构建 ----------
# 注意：本节含 rm -rf "$RT"，必须先于 mock LLM 启动（LLM pid/log 写在 $RT 下，先清场会把
# cleanup 的 pid 文件抹掉导致进程泄漏——2026-09-25 首轮冒烟实测踩过）
rm -rf "$RT"; mkdir -p "$RT/agent-config/plugins/echo-tool" "$RT/plugin-classes" "$RT/files" "$RT/workspace"
cat > "$RT/agent-config/AGENTS.md" <<'EOF'
---
name: "Plugin Smoke Agent"
vendorKey: "agentmanager"
agentKey: "plugin-smoke-agent"
version: "1.0.0"
slug: "agentmanager/plugin-smoke-agent"
description: "工具插件部署冒烟（独立进程）"
author: "@e2e"
license: "MIT"
---
插件部署冒烟专用配置。
EOF
# config.yaml：验证 ${ENV_VAR} 替换注入（PLUGIN_SMOKE_MARKER 经服务环境变量注入，configure 回调口径单测已覆盖）
cat > "$RT/agent-config/plugins/echo-tool/config.yaml" <<EOF
marker: \${PLUGIN_SMOKE_MARKER}
EOF

JAR=$(ls -t "$ROOT"/../target/agent-framework-*.jar 2>/dev/null | head -1 || true)
[ -n "${JAR:-}" ] || { note "未找到 agent-framework jar（先 mvn -DskipTests package）"; exit 1; }
AS_CORE=$(ls "$HOME"/.m2/repository/io/agentscope/agentscope-core/*/agentscope-core-*.jar 2>/dev/null | sort -V | tail -1 || true)
[ -n "${AS_CORE:-}" ] || { note "未找到 agentscope-core jar（.m2）"; exit 1; }
javac -cp "$ROOT/../target/classes:$AS_CORE" -d "$RT/plugin-classes" "$ROOT/plugin-echo/EchoToolPlugin.java"
mkdir -p "$RT/plugin-classes/META-INF/services"
echo "io.e2e.plugin.EchoToolPlugin" > "$RT/plugin-classes/META-INF/services/io.agentmanager.framework.tool.ToolPlugin"
jar --create --file "$RT/agent-config/plugins/echo-tool.jar" -C "$RT/plugin-classes" .
note "插件 jar 构建完成: $(stat -c%s "$RT/agent-config/plugins/echo-tool.jar") bytes"

# mock LLM（复用已在跑的实例；本脚本启动的实例在结束时清理）
# trap 紧随启动注册：LLM 秒崩/后续任一步骤失败时刚拉起的进程也能被回收（幂等，可重复注册）
STARTED_LLM=0
cleanup() {
  [ -n "${AGENT_PID:-}" ] && kill "$AGENT_PID" 2>/dev/null || true
  if [ "$STARTED_LLM" = "1" ] && [ -f "$RT/mock-llm.pid" ]; then
    kill "$(cat "$RT/mock-llm.pid")" 2>/dev/null || true
  fi
}
trap cleanup EXIT
if ! probe "$LLM_MOCK_PORT"; then
  MOCK_LLM_PORT=$LLM_MOCK_PORT ALLOW_SYNTH=1 nohup node "$ROOT/mock/llm-server.mjs" > "$RT/mock-llm.log" 2>&1 &
  echo $! > "$RT/mock-llm.pid"; STARTED_LLM=1
  for i in $(seq 1 20); do probe "$LLM_MOCK_PORT" && break; sleep 1; done
fi
probe "$LLM_MOCK_PORT" || { note "mock LLM 未就绪"; exit 1; }

# ---------- 2. 启动被测服务（验证默认插件回退路径 {AGENT_CONFIG_DIR}/plugins） ----------
PIDS=$(ss -ltnp 2>/dev/null | grep ":${PORT} " | grep -oP 'pid=\K[0-9]+' | sort -u || true)
for p in $PIDS; do kill -9 "$p" 2>/dev/null || true; done; sleep 1
LLM_BASE_URL="http://127.0.0.1:${LLM_MOCK_PORT}/v1" \
LLM_API_KEY="e2e-dummy" LLM_MODEL_ID="e2e-mock-model" \
CHECKPOINT_JDBC_URL="$MYSQL_URL" CHECKPOINT_USERNAME="$MYSQL_USER" CHECKPOINT_PASSWORD="$MYSQL_PASS" \
AGENT_REDIS_URL="$REDIS_URL" \
AGENT_CONFIG_DIR="$RT/agent-config" AGENT_WORKSPACE_DIR="$RT/workspace" \
SERVER_PORT="$PORT" SERVER_HOST="127.0.0.1" \
FILE_STORAGE_TYPE=local FILE_STORAGE_LOCAL_DIR="$RT/files" \
SANDBOX_ENABLED=false PLUGIN_SMOKE_MARKER=smoke-ok \
nohup java -Xms256m -Xmx768m -jar "$JAR" > "$LOG" 2>&1 &
AGENT_PID=$!
echo "$AGENT_PID" > "$RT/agent.pid"
for i in $(seq 1 90); do
  curl -sf "http://127.0.0.1:${PORT}/health" >/dev/null 2>&1 && break; sleep 1
done
curl -sf "http://127.0.0.1:${PORT}/health" >/dev/null || { note "服务启动失败，日志尾部："; tail -30 "$LOG"; exit 1; }
note "服务就绪 (port=$PORT pid=$AGENT_PID)"

tools_has() { curl -sf "http://127.0.0.1:${PORT}/tools?includeInternal=true" | grep -q "\"name\":\"$1\""; }
export PORT
export -f tools_has
BASE="http://127.0.0.1:${PORT}"

# ---------- 3. 断言 ----------
# 注册行含两个工具名（反射收集顺序不保证，顺序无关匹配）
check "启动期 Bootstrapper 注册插件（含两工具）" bash -c "grep 'Tool plugin \[echo-tool\] registered tools:' '$LOG' | grep -q echo_query && grep 'Tool plugin \[echo-tool\] registered tools:' '$LOG' | grep -q smoke_config"
check "启动期 HarnessAgentFactory 注册插件工具" bash -c "grep 'Custom tool registered:' '$LOG' | grep -q echo_query"
check "启动期 SDK Toolkit 注册插件工具" bash -c "grep -q \"Toolkit - Registered tool 'echo_query'\" '$LOG'"
check "加载汇总日志（1 插件 / 1 工具对象）" bash -c "grep -q 'Tool plugins loaded: 1 plugin(s), 1 tool(s)' '$LOG'"
check "/tools 透出插件工具 echo_query" bash -c "tools_has echo_query"
check "/tools 透出插件工具 smoke_config" bash -c "tools_has smoke_config"

# OAF reload 整包重建存活：新进程不重启，工厂重建 Toolkit 后插件工具仍在
LINES_BEFORE=$(wc -l < "$LOG")
curl -sf -X POST "$BASE/admin/reload?scope=agent" >/dev/null
sleep 3
check "OAF reload 重建后工厂重新注册插件工具" bash -c "tail -n +$((LINES_BEFORE+1)) '$LOG' | grep 'Custom tool registered:' | grep -q echo_query"
check "OAF reload 重建后 SDK Toolkit 重新注册插件工具" bash -c "tail -n +$((LINES_BEFORE+1)) '$LOG' | grep -q \"Toolkit - Registered tool 'smoke_config'\""
check "OAF reload 后 /tools 仍含 echo_query" bash -c "tools_has echo_query"

# deniedTools 类粒度剔除
cp "$RT/agent-config/AGENTS.md" "$RT/agent-config/AGENTS.md.bak"
sed -i 's/^license: "MIT"$/license: "MIT"\ndeniedTools:\n  - echo_query\n  - smoke_config/' "$RT/agent-config/AGENTS.md"
LINES_BEFORE=$(wc -l < "$LOG")
curl -sf -X POST "$BASE/admin/reload?scope=agent" >/dev/null
sleep 3
check "deniedTools 命中后工厂剔除插件工具（日志）" bash -c "tail -n +$((LINES_BEFORE+1)) '$LOG' | grep 'excluded by deniedTools' | grep -q echo_query"
if tools_has echo_query; then FAIL=$((FAIL+1)); note "FAIL  deniedTools 后 /tools 仍含 echo_query"; else PASS=$((PASS+1)); note "PASS  deniedTools 后 /tools 不再含 echo_query"; fi

# 撤销恢复
mv "$RT/agent-config/AGENTS.md.bak" "$RT/agent-config/AGENTS.md"
curl -sf -X POST "$BASE/admin/reload?scope=agent" >/dev/null
sleep 3
check "撤销 deniedTools 后插件工具恢复" bash -c "tools_has echo_query"

# ---------- 4. 汇总 ----------
note "结果：PASS=$PASS FAIL=$FAIL"
[ "$FAIL" = "0" ] || { note "失败详情，日志尾部："; tail -50 "$LOG"; exit 1; }

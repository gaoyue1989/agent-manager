#!/usr/bin/env bash
# 工具插件部署冒烟（tool-plugin-extension-plan §4.3 / §7 步骤 4），CI 门禁 e2e-plugin job：
#   1. 现场编译打包示例插件 echo-tool.jar（e2e/plugin-echo/EchoToolPlugin.java）
#   2. 独立进程起服务（MySQL/Redis/mock LLM，基础设施由 CI services 或本地 local-infra.sh 提供）
#   3. 断言：
#      · 启动注册链路：Bootstrapper / HarnessAgentFactory / SDK Toolkit 三处日志 + /tools 可见
#      · /tools?includeInternal 运行时注册集（issue #28）：internalCount 非零、declared 标注随 OAF tools 声明翻转
#      · OAF reload 整包重建后仍注册 / deniedTools 类粒度剔除 / 撤销后恢复
#      · 自定义工具三态权限（config.permission.tools）：ask 挂起→批准执行、ask 挂起→拒绝不执行
# 前置：mvn -DskipTests package 已产出 target/agent-framework-*.jar；MySQL/Redis 按 MYSQL_URL/REDIS_URL
#       指向的实例可达（CI services 或本地 local-infra.sh）。
# 用法：./scripts/plugin-smoke.sh   （结束自动清理被测进程与自启的 mock LLM；外部基础设施保留复用）
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
SMOKE_USER="plugin-smoke-user"
PASS=0; FAIL=0
note() { echo "[plugin-smoke] $*"; }
check() { # check <描述> <命令...>（命令退出码 0 记 PASS）
  local desc="$1"; shift
  if "$@" >/dev/null 2>&1; then PASS=$((PASS+1)); note "PASS  $desc";
  else FAIL=$((FAIL+1)); note "FAIL  $desc"; fi
}
check_absent() { # check_absent <描述> <命令...>（命令必须非 0 才算 PASS：用于"不应出现"的断言）
  local desc="$1"; shift
  if "$@" >/dev/null 2>&1; then FAIL=$((FAIL+1)); note "FAIL  $desc";
  else PASS=$((PASS+1)); note "PASS  $desc"; fi
}
probe() { timeout 1 bash -c "echo -n > /dev/tcp/127.0.0.1/$1" 2>/dev/null; }
# 基础设施端口从连接串推导：CI services(3306/6379) 与本地开发(3307/16379) 走同一条代码路径。
# 前缀用 [a-z:]* 以同时匹配 jdbc:mysql:// 与 redis://。
url_port() { # url_port <缺省端口> <连接串>
  local p; p=$(printf '%s' "$2" | sed -n 's#^[a-z:]*://[^:/]*:\([0-9]\{1,\}\).*#\1#p')
  printf '%s' "${p:-$1}"
}
MYSQL_PORT=$(url_port 3306 "$MYSQL_URL")
REDIS_PORT=$(url_port 6379 "$REDIS_URL")

# ---------- 0. 基础设施 ----------
# MySQL 由外部提供（CI services 或开发机既有实例），不负责拉起——不可达即环境问题，直接红。
probe "$MYSQL_PORT" || { note "MySQL($MYSQL_PORT) 不可达（MYSQL_URL=$MYSQL_URL）"; exit 1; }
if ! probe "$REDIS_PORT"; then
  # Redis 可由 local-infra.sh 自建；CI 下 services 应已就绪，起不来属环境问题不做本地兜底
  if [ "${CI:-}" = "true" ]; then
    note "Redis($REDIS_PORT) 未就绪（CI services）"
    exit 1
  fi
  note "Redis($REDIS_PORT) 未就绪，调 local-infra.sh"
  "$ROOT/scripts/local-infra.sh" >/dev/null
  for i in $(seq 1 30); do probe "$REDIS_PORT" && break; sleep 2; done
fi
probe "$REDIS_PORT" || { note "Redis($REDIS_PORT) 未就绪"; exit 1; }

# ---------- 1. 运行时目录 + 插件 jar 现场构建 ----------
# 注意：本节含 rm -rf "$RT"，必须先于 mock LLM 启动（LLM pid/log 写在 $RT 下，先清场会把
# cleanup 的 pid 文件抹掉导致进程泄漏——2026-09-25 首轮冒烟实测踩过）
rm -rf "$RT"; mkdir -p "$RT/agent-config/plugins/echo-tool" "$RT/plugin-classes" "$RT/files" "$RT/workspace"
# AGENTS.md 模板：$1=config 块内容（可为空）。三态权限用例靠切换 $1 复用同一进程。
write_agents() { # write_agents <config 块 YAML>
  cat > "$RT/agent-config/AGENTS.md" <<EOF
---
name: "Plugin Smoke Agent"
vendorKey: "agentmanager"
agentKey: "plugin-smoke-agent"
version: "1.0.0"
slug: "agentmanager/plugin-smoke-agent"
description: "工具插件部署冒烟（独立进程）"
author: "@e2e"
license: "MIT"
$1
---

插件部署冒烟专用配置。
EOF
}
write_agents ""
# 最小 logback 配置（仅控制台）：应用自带配置强写 /applog，CI 非 root 不可写致启动失败
# （同 env-up.sh §1.5，2026-09-25 实测踩过）
cat > "$RT/agent-config/logback-e2e.xml" <<'XML'
<configuration>
  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder><pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern></encoder>
  </appender>
  <root level="INFO"><appender-ref ref="CONSOLE"/></root>
</configuration>
XML
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
LOGGING_CONFIG="file:$RT/agent-config/logback-e2e.xml" \
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
reload() { curl -sf -X POST "$BASE/admin/reload?scope=agent" >/dev/null; sleep 3; }
# /tools 快照落盘：断言一律 grep 文件，避免把 JSON 内联进 bash -c 的引号地狱
tools_snapshot() { # tools_snapshot <输出文件> [includeInternal]
  if [ "${2:-}" = "includeInternal" ]; then
    curl -sf "$BASE/tools?includeInternal=true" > "$1" || echo '{}' > "$1"
  else
    curl -sf "$BASE/tools" > "$1" || echo '{}' > "$1"
  fi
}
field_num() { # field_num <快照文件> <字段名>
  grep -o "\"$2\":[0-9]*" "$1" | head -1 | cut -d: -f2
}
field_is() { # field_is <快照文件> <字段名> <最小值>
  [ "$(field_num "$1" "$2")" -ge "$3" ]
}
field_num_is() { # field_num_is <快照文件> <字段名> <期望值>
  [ "$(field_num "$1" "$2")" = "$3" ]
}
field_lt() { # field_lt <快照文件> <字段名> <上限>
  [ "$(field_num "$1" "$2")" -lt "$3" ]
}
# 单个条目内同时出现 name 与目标字段才算命中（避免跨条目误配）。
# 注意：/tools 是紧凑 JSON，/threads/:sid/history 是缩进 JSON——两者都必须先压平再匹配。
tool_entry() { # tool_entry <JSON 文件> <工具名>
  tr -d ' \n\t' < "$1" | grep -o '{[^{}]*"name":"'"$2"'"[^{}]*}' || true
}
tool_declared_is() { # tool_declared_is <快照文件> <工具名> <true|false>
  tool_entry "$1" "$2" | grep -q "\"declared\":$3"
}
# HITL 三态：发一轮对话 → 收 permission_ask 终态 → 打印 tool_call_id（空表示未挂起）
hitl_ask_tcid() { # hitl_ask_tcid <sessionId> <sse 输出文件>
  curl -sN -X POST "$BASE/threads/chat" -H 'content-type: application/json' \
    -d "{\"message\":\"[E2E:plugin:echo] 请调用 echo_query 工具\",\"userId\":\"$SMOKE_USER\",\"sessionId\":\"$1\"}" \
    > "$2" || true
  grep -o '"tool_call_id":"[^"]*"' "$2" | head -1 | cut -d'"' -f4 || true
}
confirm() { # confirm <sessionId> <tool_call_id> <true|false>
  curl -sN -X POST "$BASE/threads/$1/confirm-stream" -H 'content-type: application/json' \
    -d "{\"results\":[{\"tool_call_id\":\"$2\",\"confirmed\":$3}]}" > "$RT/confirm.sse" || true
  sleep 1  # 等 ASK 段租约释放后再读历史（抢锁竞态，同 api-core H2/H3 的 800ms 等待）
}
# 历史里的工具终态。<threads/:sid/history 是缩进 JSON，且 tool_calls 条目内含嵌套 input 对象，
# 正则无法可靠跨花括号配对——交由 node 解析后按「工具名 + state」成对判定。
history_state_is() { # history_state_is <sessionId> <工具名> <success|denied|...>
  curl -sf "$BASE/threads/$1/history" > "$RT/history.json" || echo '{}' > "$RT/history.json"
  node -e '
    const h = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"));
    const hit = (h.messages ?? []).some(m => (m.tool_calls ?? []).some(t => t.name === process.argv[2] && t.state === process.argv[3]));
    process.exit(hit ? 0 : 1);
  ' "$RT/history.json" "$2" "$3"
}

# ---------- 3. 断言 ----------
# 注册行含两个工具名（反射收集顺序不保证，顺序无关匹配）
check "启动期 Bootstrapper 注册插件（含两工具）" bash -c "grep 'Tool plugin \[echo-tool\] registered tools:' '$LOG' | grep -q echo_query && grep 'Tool plugin \[echo-tool\] registered tools:' '$LOG' | grep -q smoke_config"
check "启动期 HarnessAgentFactory 注册插件工具" bash -c "grep 'Custom tool registered:' '$LOG' | grep -q echo_query"
check "启动期 SDK Toolkit 注册插件工具" bash -c "grep -q \"Toolkit - Registered tool 'echo_query'\" '$LOG'"
check "加载汇总日志（1 插件 / 1 工具对象）" bash -c "grep -q 'Tool plugins loaded: 1 plugin(s), 1 tool(s)' '$LOG'"
check "/tools 透出插件工具 echo_query" bash -c "tools_has echo_query"
check "/tools 透出插件工具 smoke_config" bash -c "tools_has smoke_config"

# /tools?includeInternal 运行时注册集（issue #28：内部集以运行时 @Tool 注册为准，非 frontmatter 声明视图）
tools_snapshot "$RT/tools-internal.json" includeInternal
check "includeInternal 计入插件工具（internalCount>=2）" field_is "$RT/tools-internal.json" internalCount 2
check "未声明时插件工具 declared=false" tool_declared_is "$RT/tools-internal.json" echo_query false
tools_snapshot "$RT/tools-plain.json"
check "默认 /tools 不暴露内部工具（internalCount=0）" field_num_is "$RT/tools-plain.json" internalCount 0

# SDK 内置工具独立段落（issue #39 拆字段）：取自运行中 agent 的 Toolkit 实际注册集，
# 不并入 tools/totalCount/internalCount。冒烟环境 read_file 只可能来自 SDK 段；
# 下限 20 留足 feature 开关余量（实测 27，子 Agent 等按开关注册）
check "sdkInternal 段透出 SDK 内置工具（read_file）" grep -q '"name":"read_file"' "$RT/tools-internal.json"
check "sdkInternal 条目带 category=sdk 标记" grep -q '"category":"sdk"' "$RT/tools-internal.json"
check "sdkInternalCount>=20（SDK 实际注册集）" field_is "$RT/tools-internal.json" sdkInternalCount 20
check "默认 /tools sdkInternalCount=0" field_num_is "$RT/tools-plain.json" sdkInternalCount 0

# 声明 tools: [echo_query] 后 declared 标注应翻转为 true（OAF tools 声明是展示意图，非存在性开关）
write_agents 'tools:
  - echo_query

config:
  require_confirmation: false'
reload
tools_snapshot "$RT/tools-declared.json" includeInternal
BEFORE_INTERNAL=$(field_num "$RT/tools-declared.json" internalCount)
check "frontmatter tools 声明后 declared=true" tool_declared_is "$RT/tools-declared.json" echo_query true
check "声明后插件工具仍在运行时注册集（未被剔除）" bash -c "tools_has echo_query"

# OAF reload 整包重建存活：新进程不重启，工厂重建 Toolkit 后插件工具仍在
LINES_BEFORE=$(wc -l < "$LOG")
reload
check "OAF reload 重建后工厂重新注册插件工具" bash -c "tail -n +$((LINES_BEFORE+1)) '$LOG' | grep 'Custom tool registered:' | grep -q echo_query"
check "OAF reload 重建后 SDK Toolkit 重新注册插件工具" bash -c "tail -n +$((LINES_BEFORE+1)) '$LOG' | grep -q \"Toolkit - Registered tool 'smoke_config'\""
check "OAF reload 后 /tools 仍含 echo_query" bash -c "tools_has echo_query"

# deniedTools 类粒度剔除
cp "$RT/agent-config/AGENTS.md" "$RT/agent-config/AGENTS.md.bak"
sed -i 's/^license: "MIT"$/license: "MIT"\ndeniedTools:\n  - echo_query\n  - smoke_config/' "$RT/agent-config/AGENTS.md"
LINES_BEFORE=$(wc -l < "$LOG")
reload
check "deniedTools 命中后工厂剔除插件工具（日志）" bash -c "tail -n +$((LINES_BEFORE+1)) '$LOG' | grep 'excluded by deniedTools' | grep -q echo_query"
if tools_has echo_query; then FAIL=$((FAIL+1)); note "FAIL  deniedTools 后 /tools 仍含 echo_query"; else PASS=$((PASS+1)); note "PASS  deniedTools 后 /tools 不再含 echo_query"; fi
tools_snapshot "$RT/tools-denied.json" includeInternal
# 内置工具（echo/present_file 等）不受插件级 deniedTools 影响，这里只要求总数收敛下降
check "deniedTools 后 internalCount 同步收敛（${BEFORE_INTERNAL} → $(field_num "$RT/tools-denied.json" internalCount)）" \
  field_lt "$RT/tools-denied.json" internalCount "$BEFORE_INTERNAL"
# SDK 段只剔除命中 deniedTools 的名字，插件级剔除不应波及 SDK 内置集
BEFORE_SDK=$(field_num "$RT/tools-declared.json" sdkInternalCount)
check "deniedTools 后 sdkInternal 不受影响（${BEFORE_SDK} → $(field_num "$RT/tools-denied.json" sdkInternalCount)）" \
  field_num_is "$RT/tools-denied.json" sdkInternalCount "$BEFORE_SDK"

# 撤销恢复
mv "$RT/agent-config/AGENTS.md.bak" "$RT/agent-config/AGENTS.md"
reload
check "撤销 deniedTools 后插件工具恢复" bash -c "tools_has echo_query"

# ---------- 4. 自定义工具三态权限（config.permission.tools，15ae771） ----------
# ask：工具调用挂起等人工确认；批准后执行、拒绝后不执行。deny 侧由权限引擎直接拒绝，不挂起。
write_agents 'config:
  require_confirmation: false
  permission:
    tools:
      echo_query: ask'
reload
SID_ASK="ps-ask-$(date +%s)"
TCID=$(hitl_ask_tcid "$SID_ASK" "$RT/ask.sse")
check "config.permission.tools=ask 触发 permission_ask 挂起" grep -q '"type":"permission_ask"' "$RT/ask.sse"
check "permission_ask 指向插件工具 echo_query" bash -c "grep -q '\"name\":\"echo_query\"' '$RT/ask.sse'"
check "挂起后 /threads/:sid/status 为 waiting_confirm" bash -c \
  "curl -sf '$BASE/threads/$SID_ASK/status' | grep -q '\"state\":\"waiting_confirm\"'"

confirm "$SID_ASK" "${TCID:-none}" true
check "ask 批准后工具执行成功" history_state_is "$SID_ASK" echo_query success

# 拒绝路径：同一 ask 规则，改传 confirmed=false
SID_DENY="ps-deny-$(date +%s)"
TCID_DENY=$(hitl_ask_tcid "$SID_DENY" "$RT/deny-ask.sse")
check "ask 拒绝路径同样先挂起" grep -q '"type":"permission_ask"' "$RT/deny-ask.sse"
confirm "$SID_DENY" "${TCID_DENY:-none}" false
check "ask 拒绝后工具未执行（state=denied）" history_state_is "$SID_DENY" echo_query denied

# deny 侧：权限引擎直接拒绝，不产生挂起
write_agents 'config:
  require_confirmation: false
  permission:
    tools:
      echo_query: deny'
reload
SID_HARD="ps-hard-$(date +%s)"
hitl_ask_tcid "$SID_HARD" "$RT/hard-ask.sse"
check_absent "config.permission.tools=deny 不挂起（无 permission_ask）" grep -q '"type":"permission_ask"' "$RT/hard-ask.sse"
check "deny 后工具被拒绝（state=denied）" history_state_is "$SID_HARD" echo_query denied

# ---------- 5. 收尾恢复默认配置 ----------
write_agents ""
reload
check "收尾恢复默认配置后插件工具仍注册" bash -c "tools_has echo_query"

# ---------- 6. 汇总 ----------
note "结果：PASS=$PASS FAIL=$FAIL"
[ "$FAIL" = "0" ] || { note "失败详情，日志尾部："; tail -50 "$LOG"; exit 1; }

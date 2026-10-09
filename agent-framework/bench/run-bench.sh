#!/usr/bin/env bash
# =========================================================================
# run-bench.sh — 并发压测一键编排（concurrency-benchmark-plan.md §8/§10）
#
# 建库 → 起 mock-llm/mock-mcp → 逐场景拉起被测容器（1C/1G 硬限）→ 预热 →
# 逐档执行（15s ramp + 180s 稳态）→ 汇总报告 → 清理（容器/mock/bench 沙箱）
#
# 参数（环境变量可覆盖）：
#   SCENARIOS=B0,B1,B3,B5   场景子集（全矩阵 B0,B1,B2,B3,B4,B5；C=并发启动延迟档，issue #87）
#   STAGES=1,2,4,8,10,16,32 并发阶梯（B 档；C 档固定 C_STAGES=2,4,8）
#   C_STAGES=2,4,8          C 档并发阶梯
#   SESSION_POOL=10         会话池上限（沙箱隔离键=sessionId，池大小≈沙箱数）
#   SANDBOX_MEMORY_MB=256   单沙箱内存上限（方案原定 512Mi；宿主 8G 与常驻 kind 平台共存时
#                           10×512Mi 会触发宿主 swap 抖动/OOM，实测工作集约 100Mi 故降为 256Mi）
#   STAGE_SECONDS=180       每档稳态时长
#   RAMP_SECONDS=15         每档 ramp 时长（C 档恒 0，一波齐发）
#   ASSERT_START_DELAY_MS=10000  C 档启动延迟门禁：任一路 AGENT_START 超阈值该档 FAIL（exit 3）
#   P95_ABORT_MS=30000      档停止条件：稳态 P95 超阈值该档中止（exit 2）；
#                           必须显式大于 MOCK_LLM_SLOW_MS（C 档 slow turn 总时延 ≈ 排队 + SLOW_MS）
#   MOCK_LLM_SLOW_MS=12000  mock-llm slow 场景单 turn 时长（C 档在途 turn 构造）；显式导出给
#                           mock-llm，取值论证见下
#   IMAGE=agent-framework:latest
#   BASE_URL=http://127.0.0.1:8101
# =========================================================================
set -u
cd "$(dirname "$0")"
BENCH_DIR="$(pwd)"
RESULTS="$BENCH_DIR/results"
mkdir -p "$RESULTS"

SCENARIOS="${SCENARIOS:-B0,B1,B3,B5}"
STAGES="${STAGES:-1,2,4,8,10,16,32}"
C_STAGES="${C_STAGES:-2,4,8}"
SESSION_POOL="${SESSION_POOL:-10}"
STAGE_SECONDS="${STAGE_SECONDS:-180}"
RAMP_SECONDS="${RAMP_SECONDS:-15}"
P95_SLO_MS="${P95_SLO_MS:-10000}"
ASSERT_START_DELAY_MS="${ASSERT_START_DELAY_MS:-10000}"
# P95 停止条件显式下传 runner（--p95-abort-ms），并与 MOCK_LLM_SLOW_MS 解耦（issue #97 问题 14）：
# 此前 mock 默认 SLOW_MS=30000 恰撞 runner 默认 P95_ABORT_MS=30000 且未显式传参 → C 档 slow
# turn 总时延 ≥30s 必触发 P95 中止（C=4 必 abort、C=8 永不执行）。取 12000：缺陷形态下
# C=2 排队签名 ≈SLOW_MS=12s 仍 > 10s 门禁阈值（8000 会漏检 C=2 单档），且与 30000 有 2.5× 余量
P95_ABORT_MS="${P95_ABORT_MS:-30000}"
MOCK_LLM_SLOW_MS="${MOCK_LLM_SLOW_MS:-12000}"
IMAGE="${IMAGE:-agent-framework:latest}"
BASE_URL="${BASE_URL:-http://127.0.0.1:8101}"
CONTAINER="${CONTAINER:-bench-agent-fw}"
MYSQL_HOST="${MYSQL_HOST:-172.17.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3308}"
MYSQL_USER="${MYSQL_USER:-agent_manager}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-Agent@Manager2026}"
# 压测专用 MySQL 8.0 容器（宿主 3307 为 GreatSQL 且无 root 建库权限；
# 专用实例隔离性更强，GET_LOCK/连接池语义不变。指定 MYSQL_HOST/PORT 可改用外部实例）
MYSQL_CONTAINER="${MYSQL_CONTAINER:-bench-mysql}"
OPENSANDBOX_URL="${OPENSANDBOX_URL:-http://127.0.0.1:8090}"
# 真实 key（列表/删除接口需要；可从 release-agent ConfigMap 获取后传入）
OPENSANDBOX_API_KEY="${OPENSANDBOX_API_KEY:-}"
export OPENSANDBOX_API_KEY
MOCK_LLM_PORT=18081
MOCK_MCP_PORT=18082

LLM_PID=""
MCP_PID=""
say() { echo -e "\033[34m== $* ==\033[0m"; }

cleanup() {
  say "清理"
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  [ -n "$LLM_PID" ] && kill "$LLM_PID" 2>/dev/null || true
  [ -n "$MCP_PID" ] && kill "$MCP_PID" 2>/dev/null || true
  [ -n "$MYSQL_STARTED" ] && docker rm -f "$MYSQL_CONTAINER" >/dev/null 2>&1 || true
  cleanup_bench_sandboxes
}
trap cleanup EXIT INT TERM

# 压测沙箱清理：沙箱隔离键=sessionId，映射存在 bench 库 agent_state（OpenSandboxState.sandboxId）。
# 从库反查本场景创建的 sandbox id 精确删除（共享单点，禁止整库清理）
cleanup_bench_sandboxes() {
  local ids
  ids=$(mysql -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -N -e \
    "SELECT state_data FROM agent_manager_bench.agent_state \
     WHERE session_id LIKE '%sandbox/user/Bench Agent/bench-%'" 2>/dev/null \
    | grep -oE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | sort -u)
  if [ -z "$ids" ]; then
    echo "  [sandbox] 库中无 bench 沙箱记录"
    return 0
  fi
  local auth=()
  [ -n "$OPENSANDBOX_API_KEY" ] && auth=(-H "OPEN-SANDBOX-API-KEY: $OPENSANDBOX_API_KEY")
  for id in $ids; do
    echo "  [sandbox] 删除 $id"
    curl -fsS -m 10 -X DELETE "$OPENSANDBOX_URL/v1/sandboxes/$id" "${auth[@]}" >/dev/null 2>&1 || true
  done
}

wait_health() {
  local url="$1" tries="${2:-60}"
  for _ in $(seq 1 "$tries"); do
    if curl -fsS -m 3 "$url" >/dev/null 2>&1; then return 0; fi
    sleep 2
  done
  return 1
}

# ===== 0. 前置检查 =====
say "前置检查"
for cmd in docker node curl jq mysql; do
  command -v "$cmd" >/dev/null || { echo "缺少 $cmd"; exit 1; }
done
# 压测 MySQL：不可达则拉起专用容器（幂等；已有外部实例时跳过）
MYSQL_STARTED=""
if ! mysql -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -e "SELECT 1" >/dev/null 2>&1; then
  if [ "$MYSQL_HOST" != "172.17.0.1" ] || ! docker ps --format '{{.Names}}' | grep -qx "$MYSQL_CONTAINER"; then
    say "启动压测 MySQL 容器 $MYSQL_CONTAINER（:$MYSQL_PORT）"
    docker rm -f "$MYSQL_CONTAINER" >/dev/null 2>&1 || true
    docker run -d --name "$MYSQL_CONTAINER" -p "$MYSQL_PORT:3306" \
      -e MYSQL_ROOT_PASSWORD=bench-root-pass \
      -e MYSQL_DATABASE=agent_manager_bench \
      -e MYSQL_USER="$MYSQL_USER" -e MYSQL_PASSWORD="$MYSQL_PASSWORD" \
      mysql:8.0 >/dev/null || { echo "MySQL 容器启动失败"; exit 1; }
    MYSQL_STARTED=1
    for _ in $(seq 1 30); do
      mysql -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -e "SELECT 1" >/dev/null 2>&1 && break
      sleep 2
    done
  fi
  mysql -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -e "SELECT 1" >/dev/null 2>&1 \
    || { echo "MySQL $MYSQL_HOST:$MYSQL_PORT 仍不可达"; exit 1; }
fi
docker image inspect "$IMAGE" >/dev/null 2>&1 || { echo "镜像 $IMAGE 不存在"; exit 1; }
if echo ",$SCENARIOS," | grep -qE ",B[1-5]," ; then
  curl -fsS -m 5 "$OPENSANDBOX_URL/health" >/dev/null || { echo "OpenSandbox $OPENSANDBOX_URL 不可达（沙箱场景需要）"; exit 1; }
fi
# 宿主端口占用检查（避开常驻服务）
for port in $MOCK_LLM_PORT $MOCK_MCP_PORT 8101; do
  if curl -fsS -m 2 "http://127.0.0.1:$port/health" >/dev/null 2>&1; then
    echo "端口 $port 已有服务响应 /health，可能残留上次运行"; exit 1
  fi
done

# ===== 1. 建库 =====
say "初始化压测库 agent_manager_bench"
mysql -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" < sql/init-bench-db.sql

# ===== 2. 启动 mock 服务 =====
say "启动 mock-llm / mock-mcp"
# 显式导出 slow 时长：不导出则 mock 用自身默认 30000，撞 P95_ABORT_MS 使 C 档必 abort
# （mock 对非法值（非数字/负数）会启动即 exit 1，见 mock-llm/server.js readMsEnv）
export MOCK_LLM_SLOW_MS
(cd mock-llm && exec node server.js) >>"$RESULTS/mock-llm.log" 2>&1 &
LLM_PID=$!
(cd mock-mcp && exec node server.js) >>"$RESULTS/mock-mcp.log" 2>&1 &
MCP_PID=$!
wait_health "http://127.0.0.1:$MOCK_LLM_PORT/health" 15 || { echo "mock-llm 启动失败"; exit 1; }
wait_health "http://127.0.0.1:$MOCK_MCP_PORT/health" 15 || { echo "mock-mcp 启动失败"; exit 1; }
echo "  mock-llm  :$MOCK_LLM_PORT (pid $LLM_PID)"
echo "  mock-mcp  :$MOCK_MCP_PORT (pid $MCP_PID)"

# ===== 3. 被测容器管理 =====
CURRENT_SANDBOX=""
start_container() {
  local flag="$1"   # true/false
  if [ "$flag" == "$CURRENT_SANDBOX" ] && curl -fsS -m 3 "$BASE_URL/health" >/dev/null 2>&1; then
    return 0
  fi
  say "拉起被测容器（SANDBOX_ENABLED=$flag，1C/1G）"
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  # --user root：命名卷根目录属 root，appuser 无法创建 /data/files 与工作区（本地压测可接受）
  # 日志轮转：INFO 级逐请求日志在高压下每小时可达数十 GB，必须限宽（--log-opt）
  docker run -d --name "$CONTAINER" --user root \
    --cpus=1 --memory=1g --memory-swap=1g \
    --log-driver json-file --log-opt max-size=50m --log-opt max-file=2 \
    -p 8101:8100 \
    -v "$BENCH_DIR/agent:/config:ro" \
    -v "${CONTAINER}-ws:/workspace" \
    -v "${CONTAINER}-data:/data" \
    -e SERVER_PORT=8100 \
    -e AGENT_CONFIG_DIR=/config \
    -e AGENT_WORKSPACE_DIR=/workspace \
    -e LLM_API_KEY=bench-mock \
    -e LLM_MODEL_ID=bench-model \
    -e LLM_BASE_URL=http://172.17.0.1:$MOCK_LLM_PORT/v1 \
    -e AGENT_REDIS_URL="${AGENT_REDIS_URL:-redis://172.17.0.1:16379}" \
    -e CHECKPOINT_JDBC_URL="jdbc:mysql://172.17.0.1:$MYSQL_PORT/agent_manager_bench" \
    -e CHECKPOINT_USERNAME="$MYSQL_USER" \
    -e CHECKPOINT_PASSWORD="$MYSQL_PASSWORD" \
    -e SANDBOX_ENABLED=$flag \
    -e SANDBOX_MEMORY_MB="${SANDBOX_MEMORY_MB:-256}" \
    -e SANDBOX_TIMEOUT_MINUTES=240 \
    -e OPENSANDBOX_SERVER_URL=172.17.0.1:8090 \
    -e OPENSANDBOX_API_KEY="${OPENSANDBOX_API_KEY:-bench-placeholder}" \
    "$IMAGE" >/dev/null || { echo "容器启动失败"; exit 1; }
  echo "  等待服务就绪（首次启动建表 + MCP 注册）..."
  wait_health "$BASE_URL/health" 90 || { echo "服务未在超时内就绪"; docker logs "$CONTAINER" | tail -20; exit 1; }
  CURRENT_SANDBOX="$flag"
  echo "  就绪：$BASE_URL"
}

# ===== 4. 逐场景执行 =====
# 历史结果归档（防跨次混写：jsonl 为追加写，旧档会污染报告）
if ls results/B[0-9] results/C >/dev/null 2>&1; then
  ARCHIVE="results/archive-$(date +%H%M%S)"
  mkdir -p "$ARCHIVE"
  mv results/B[0-9] results/C "$ARCHIVE"/ 2>/dev/null || true
  echo "  历史结果已归档至 $ARCHIVE"
fi
for SC in $(echo "$SCENARIOS" | tr ',' ' '); do
  say "场景 $SC"
  SANDBOX_FLAG=true
  { [ "$SC" == "B0" ] || [ "$SC" == "C" ]; } && SANDBOX_FLAG=false
  start_container "$SANDBOX_FLAG"

  # 会话池（沙箱隔离键=sessionId）：B5 固定 1（单会话并发 → turn 租约串行化上界），
  # 其余 min(SESSION_POOL, 池上限)；C ≤ S 每会话独占，C > S 会话共享触发租约排队。
  # C（并发启动延迟档）：每 worker 独占会话（测跨会话排队而非同会话租约排队）
  SC_POOL="${SESSION_POOL:-10}"
  [ "$SC" == "B5" ] && SC_POOL=1

  # 场景级并发阶梯：C 档固定 2/4/8（设计 §4.2），B 档用全局 STAGES
  STAGE_LIST=$(echo "$STAGES" | tr ',' ' ')
  SC_RAMP="$RAMP_SECONDS"
  if [ "$SC" == "C" ]; then
    STAGE_LIST=$(echo "${C_STAGES:-2,4,8}" | tr ',' ' ')
    SC_RAMP=0   # 一波齐发：排队窗口从第 0 秒开始
  fi

  # 预热：每会话串行 1 次（沙箱全部呈已创建态，create 开销与稳态分离）。
  # C 档只暖 1 轮（session-pool 1）：冷 1C JVM 首轮多并发会出现秒级 JIT/类加载抖动，
  # 不暖机会让启动延迟门禁吃假阳性（实测 21s 离群，复跑即消失）；全池预热 = 池×30s 不划算
  C_WARM_POOL="$SC_POOL"
  [ "$SC" == "C" ] && C_WARM_POOL=1
  say "场景 $SC 预热（sessions=$C_WARM_POOL）"
  node load/runner.js --mode warmup --scenario "$SC" --session-pool "$C_WARM_POOL" \
    --base-url "$BASE_URL" --results-dir "$RESULTS" || { echo "  预热失败，跳过场景 $SC"; continue; }

  for C in $STAGE_LIST; do
    # C ≤ 池上限时每个 inflight 独占会话（C 档池 = 并发数，恒独占）
    STAGE_POOL=$SC_POOL
    { [ "$SC" != "B5" ] && [ "$SC" != "C" ]; } && [ "$C" -lt "$STAGE_POOL" ] && STAGE_POOL=$C
    [ "$SC" == "C" ] && STAGE_POOL=$C
    say "场景 $SC 档 C=$C（U=$STAGE_POOL）"
    curl -fsS -m 5 -X POST "http://127.0.0.1:$MOCK_LLM_PORT/reset" >/dev/null
    curl -fsS -m 5 -X POST "http://127.0.0.1:$MOCK_MCP_PORT/reset" >/dev/null
    # 会话池经 --session-pool 下发（与 runner.js 的 arg 名一致；此前误写 --user-pool 未生效）
    if node load/runner.js --mode run --scenario "$SC" --stage "$C" --concurrency "$C" \
      --session-pool "$STAGE_POOL" --ramp-seconds "$SC_RAMP" --stage-seconds "$STAGE_SECONDS" \
      --assert-start-delay-ms "$ASSERT_START_DELAY_MS" --p95-abort-ms "$P95_ABORT_MS" \
      --base-url "$BASE_URL" --results-dir "$RESULTS"; then RC=0; else RC=$?; fi
    # LLM 调用对账落盘（期望值已记录在 summary）
    curl -fsS -m 5 "http://127.0.0.1:$MOCK_LLM_PORT/stats" \
      | jq '{llmCallsActual: .total, byScenario: .byScenario}' \
      > "$RESULTS/$SC/$C.llmstats.json" 2>/dev/null || true
    if [ "$RC" -eq 2 ]; then
      echo "  档 C=$C 触发停止条件，场景 $SC 剩余档跳过"
      break
    fi
    if [ "$RC" -eq 3 ]; then
      echo "  档 C=$C 启动延迟门禁 FAIL（跨会话排队，issue #87），场景 $SC 剩余档跳过"
      break
    fi
    [ "$RC" -ne 0 ] && { echo "  runner 异常退出（rc=$RC），场景 $SC 剩余档跳过"; break; }
  done
done

# ===== 5. 报告 =====
say "生成报告"
node load/report.js --results-dir "$RESULTS"

# ===== 6. 清理（trap 兜底：容器/mock/沙箱） =====
say "完成（容器、mock 进程与 bench 沙箱经 trap 清理；库 agent_manager_bench 保留供复跑）"

#!/usr/bin/env bash
# E2E：会话级工具列表（docs/design/session-tool-list-design.md）
# 场景：
#   P   构造并发布含 2 个 MCP server（各 1 工具）的配置包（自定义工具 echo/get_current_time
#       为平台内置 @Tool bean，随镜像自带，无需打包）
#   E1  查询接口闭环：/tools 视图 fullName/sessionControllable/sessionTools 汇总
#   E2  chat {tools:[query]}：模型侧 request.tools 仅 query + 内置（llm-calls 证据）
#   E3  下一轮不传 tools：恢复全部（仅本轮生效，不落库）
#   E4  chat {tools:[]}：仅内置（MCP/自定义全剔除）
#   E5  全名别名 mcp__tools-a__query 生效；内置名入名单可见性不变；未知名忽略
#   E6  白名单外调用兜底 DENY：诱导 search 调用 → mock /stats 执行计数 0（强证据）
#   E7  自定义工具白名单：tools:[echo] → 含 echo、不含 query
#   E8  A2A message.metadata.tools 生效
#   E8B A2A messageId 撞保留键 sessionId（issue #294：分组键前缀化，会话路由不劣化）
#   E9  双副本：任意副本续聊同口径（无状态）
# 前置：platform-backend running；新 agent-framework:latest 镜像已导入 kind 节点；
#       宿主机 8814/8815 端口可被集群 Pod 访问（mock MCP server 本脚本自起自停）
# 用法：BASE/A2A_BASE/RUNTIME_IMAGE/MCP_HOST 可覆盖
set -u
BASE="${BASE:-http://localhost:30080/api/v1}"
A2A_BASE="${A2A_BASE:-http://localhost:30080/agent}"
NS=agent-platform
SVC_NAME="${SVC_NAME:-e2e-session-tools}"
PKG_NAME="Session Tools E2E"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PASS=0; FAIL=0
declare -a FAILED_CASES
MOCK_PIDS=()

say()  { echo -e "\n\033[1;34m== $* ==\033[0m"; }
ok()   { PASS=$((PASS+1)); echo "  \033[32mPASS\033[0m $1"; }
bad()  { FAIL=$((FAIL+1)); FAILED_CASES+=("$1"); echo "  \033[31mFAIL\033[0m $1"; }
assert_eq() { if [ "$2" == "$3" ]; then ok "$1"; else bad "$1 (got: $2, want: $3)"; fi; }
assert_contains() { case "$2" in *"$3"*) ok "$1";; *) bad "$1 (missing: $3)";; esac; }
assert_not_contains() { case "$2" in *"$3"*) bad "$1 (should NOT contain: $3)";; *) ok "$1";; esac; }

if [ -z "${LLM_API_KEY:-}" ] || [ -z "${LLM_MODEL:-}" ] || [ -z "${LLM_ENDPOINT:-}" ]; then
  source "$(dirname "$0")/../.env.secrets" 2>/dev/null || source /root/agent-manager/.env.secrets
fi
export LLM_API_KEY LLM_MODEL LLM_ENDPOINT

# 业务 Pod 访问宿主机 mock MCP 的地址：宿主机主 IP（kind Pod → 宿主机路由可达，
# 与 OPENSANDBOX_SERVER_URL 同一可达性假设）
MCP_HOST="${MCP_HOST:-$(hostname -I 2>/dev/null | awk '{print $1}')}"
MCP_HOST="${MCP_HOST:-192.168.31.155}"
MCP_A_PORT="${MCP_A_PORT:-8814}"
MCP_B_PORT="${MCP_B_PORT:-8815}"

api() {
  local m=$1 p=$2 b=${3:-}
  if [ -n "$b" ]; then
    curl -s -X "$m" "$BASE$p" -H 'Content-Type: application/json' -d "$b"
  else
    curl -s -X "$m" "$BASE$p"
  fi
}
upload_zip() { curl -s -X POST "$BASE/packages" -F "file=@$1"; }
wait_status() {
  local id=$1 want=$2 timeout=${3:-300} waited=0 got=""
  while true; do
    got=$(api GET "/services/$id" | jq -r '.data.status')
    [ "$got" == "$want" ] && return 0
    [ $waited -ge $timeout ] && { echo "  timeout waiting $want (last=$got)"; return 1; }
    sleep 5; waited=$((waited+5))
  done
}
wait_endpoint_json() {
  local url=$1 timeout=${2:-90} waited=0 body=""
  while true; do
    body=$(curl -s --max-time 10 "$url")
    [ -n "$body" ] && echo "$body" | python3 -c "import json,sys; json.load(sys.stdin)" 2>/dev/null && { echo "$body"; return 0; }
    [ $waited -ge $timeout ] && { echo ""; return 1; }
    sleep 3; waited=$((waited+3))
  done
}

chat_sse() { # chat_sse <svc> <sessionId> <message> [toolsJsonOrEmpty] [userId] [skillsJsonOrEmpty]
  local svc=$1 sid=$2 msg=$3 tools=${4:-} uid=${5:-debug-user} skills=${6:-}
  python3 - "$svc" "$sid" "$msg" "$tools" "$uid" "$A2A_BASE" "$skills" <<'PY'
import json, sys, urllib.request
svc, sid, msg, tools, uid, base, skills = sys.argv[1:8]
body = {"message": msg, "sessionId": sid, "userId": uid}
if tools != "":
    body["tools"] = json.loads(tools)
if skills != "":
    body["skills"] = json.loads(skills)
req = urllib.request.Request(base + "/" + svc + "/threads/chat",
    data=json.dumps(body).encode(),
    headers={"Content-Type": "application/json", "Accept": "text/event-stream"})
out = []
try:
    with urllib.request.urlopen(req, timeout=600) as resp:
        for raw in resp:
            out.append(raw.decode("utf-8", "replace"))
except Exception as e:
    out.append("STREAM_ERROR: %s" % e)
print("".join(out))
PY
}
llm_calls() { curl -s --max-time 30 "$A2A_BASE/$1/threads/$2/llm-calls"; }
calls_count() { llm_calls "$1" "$2" | jq -r '.calls | length' 2>/dev/null || echo 0; }
new_calls() { llm_calls "$1" "$2" | jq -c --argjson n "$3" '.calls[$n:]' 2>/dev/null; }
# 本轮 LLM 请求的 tools 名列表（每次模型调用的 request.tools；取本轮新增 calls）
turn_tool_names() {
  python3 -c "
import json,sys
calls=json.load(sys.stdin)
out=[]
for c in calls:
    tools=(c.get('request') or {}).get('tools') or []
    out.append(sorted(str(t.get('name') or t.get('function',{}).get('name') or '') for t in tools))
print('\n'.join(json.dumps(x) for x in out))"
}
# 本轮（新增 calls 中最后一次调用）的 tools 名并集
last_call_tool_names() { turn_tool_names | tail -1; }

cleanup_mine() {
  local id
  id=$(api GET "/services" | jq -r --arg n "$SVC_NAME" '.data[]? | select(
      (.k8sName // "") == ("oaf-" + $n) or (.shortName // "") == $n
      or (.displayName // "") == $n or ((.k8sName // "") | endswith($n))) | .id' | head -1)
  [ -n "$id" ] && [ "$id" != "null" ] && api DELETE "/services/$id" >/dev/null && echo "  cleaned old service $SVC_NAME (id=$id)"
  local pid
  pid=$(api GET "/packages" | jq -r --arg n "$PKG_NAME" '.data[]? | select(.name==$n) | .id' | head -1)
  [ -n "$pid" ] && [ "$pid" != "null" ] && api DELETE "/packages/$pid" >/dev/null && echo "  cleaned old package (id=$pid)"
}

AGENTS_MD='---
name: "Session Tools E2E"
vendorKey: "e2e"
agentKey: "session-tools"
version: "1.0.0"
slug: "e2e/session-tools"
description: "Session tool list E2E agent"
author: "@e2e"
license: "MIT"

mcpServers:
  - vendor: local
    server: tools-a
    version: "1.0.0"
    configDir: mcp-configs/tools-a
  - vendor: local
    server: tools-b
    version: "1.0.0"
    configDir: mcp-configs/tools-b
skills:
  - name: demo-skill
    source: local
    version: "1.0.0"
---

# Session Tools E2E Agent

You are a test agent for OAF session tool list E2E. Answer briefly.
Use tools when explicitly asked to call one.
'

###############################################################################
say "P：启动 mock MCP server + 构造发布配置包"
cleanup_mine

python3 "$SCRIPT_DIR/mock-mcp-server.py" "$MCP_A_PORT" tools-a query &
MOCK_PIDS+=($!)
python3 "$SCRIPT_DIR/mock-mcp-server.py" "$MCP_B_PORT" tools-b search &
MOCK_PIDS+=($!)
trap 'for p in "${MOCK_PIDS[@]:-}"; do kill "$p" 2>/dev/null; done; [ -n "${WORKDIR:-}" ] && rm -rf "$WORKDIR"' EXIT
sleep 1
curl -sf "http://127.0.0.1:$MCP_A_PORT/health" >/dev/null && ok "P1 mock MCP tools-a 就绪 (:${MCP_A_PORT})" || { bad "P1 mock tools-a 启动失败"; exit 1; }
curl -sf "http://127.0.0.1:$MCP_B_PORT/health" >/dev/null && ok "P2 mock MCP tools-b 就绪 (:${MCP_B_PORT})" || { bad "P2 mock tools-b 启动失败"; exit 1; }

WORKDIR=$(mktemp -d)
printf '%s' "$AGENTS_MD" > "$WORKDIR/AGENTS.md"
mkdir -p "$WORKDIR/mcp-configs/tools-a" "$WORKDIR/mcp-configs/tools-b" "$WORKDIR/skills/demo-skill"
printf -- '---\nname: demo-skill\ndescription: E2E demo skill for combined whitelist\nversion: 1.0.0\n---\n\n# demo-skill\n\nDEMO_SKILL_BODY_5c8e\n' \
  > "$WORKDIR/skills/demo-skill/SKILL.md"
printf 'vendor: "local"\nserver: "tools-a"\nversion: "1.0.0"\n\nconnection:\n  type: "streamableHttp"\n  url: "http://%s:%s"\n' "$MCP_HOST" "$MCP_A_PORT" > "$WORKDIR/mcp-configs/tools-a/config.yaml"
printf 'vendor: "local"\nserver: "tools-b"\nversion: "1.0.0"\n\nconnection:\n  type: "streamableHttp"\n  url: "http://%s:%s"\n' "$MCP_HOST" "$MCP_B_PORT" > "$WORKDIR/mcp-configs/tools-b/config.yaml"
ZIPFILE="$WORKDIR/session-tools-agent.zip"
(cd "$WORKDIR" && zip -qr "$ZIPFILE" AGENTS.md mcp-configs skills)
UP=$(upload_zip "$ZIPFILE")
PKG_ID=$(echo "$UP" | jq -r '.data.id')
[ -n "$PKG_ID" ] && [ "$PKG_ID" != "null" ] && ok "P3 上传配置包 (packageId=$PKG_ID)" || { bad "P3 上传失败: $UP"; exit 1; }

RUNTIME_IMAGE="${RUNTIME_IMAGE:-$(api GET /images | jq -r '[.data[].Image | select(. != null and contains(":latest"))][0] // [.data[].Image | select(. != null and contains("5001"))][0] // .data[0].Image // empty')}"
PUB=$(api POST /services "{\"packageId\":$PKG_ID,\"image\":\"$RUNTIME_IMAGE\",\"env\":$(python3 -c "
import json,os
print(json.dumps({'LLM_API_KEY':os.environ['LLM_API_KEY'],'LLM_MODEL_ID':os.environ['LLM_MODEL'],
 'LLM_BASE_URL':os.environ['LLM_ENDPOINT'],
 'CHECKPOINT_JDBC_URL':'jdbc:mysql://oaf-mysql.$NS.svc.cluster.local:3306/oaf_checkpoint?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC',
 'CHECKPOINT_USERNAME':'oaf','CHECKPOINT_PASSWORD':'OafPlatform2026',
 'AGENT_REDIS_URL':'redis://oaf-redis.$NS.svc.cluster.local:6379'}))")}")
SVC_ID=$(echo "$PUB" | jq -r '.data.id')
[ -n "$SVC_ID" ] && [ "$SVC_ID" != "null" ] && ok "P4 发布受理 (svcId=$SVC_ID, image=$RUNTIME_IMAGE)" || { bad "P4 发布失败: $PUB"; exit 1; }
if wait_status "$SVC_ID" running 300; then ok "P5 服务 running"; else bad "P5 未达 running"; exit 1; fi
SVC_URL="$A2A_BASE/$SVC_NAME"
wait_endpoint_json "$SVC_URL/tools" 120 >/dev/null || { bad "P6 服务端点未就绪"; exit 1; }

say "E1：查询接口闭环（/tools 视图）"
TOOLS_VIEW=$(curl -s "$SVC_URL/tools?includeInternal=true")
assert_contains "1.1 MCP 条目带 fullName" "$TOOLS_VIEW" '"fullName":"mcp__tools-a__query"'
assert_contains "1.2 MCP 条目 sessionControllable=true" "$TOOLS_VIEW" '"sessionControllable":true'
ST=$(echo "$TOOLS_VIEW" | jq -r '.sessionTools.tools | join(",")')
assert_contains "1.3 sessionTools 含 MCP query" "$ST" "query"
assert_contains "1.4 sessionTools 含 MCP search" "$ST" "search"
assert_contains "1.5 sessionTools 含自定义 echo" "$ST" "echo"
assert_contains "1.6 sessionTools 含自定义 get_current_time" "$ST" "get_current_time"
SDKCnt=$(echo "$TOOLS_VIEW" | jq -r '[.sdkInternal[]? | select(.sessionControllable == false)] | length')
assert_eq "1.7 sdkInternal 段 sessionControllable 恒 false" "$SDKCnt" "$(echo "$TOOLS_VIEW" | jq -r '.sdkInternalCount')"
# app_only/白名单预览：?tools= 只裁剪业务段
WL=$(curl -s "$SVC_URL/tools?includeInternal=true&tools=query")
assert_contains "1.8 ?tools=query 白名单裁剪生效" "$WL" '"name":"query"'
WL_SDK=$(echo "$WL" | jq -r '.sdkInternalCount')
assert_eq "1.9 SDK 内置段不受白名单影响" "$WL_SDK" "$(echo "$TOOLS_VIEW" | jq -r '.sdkInternalCount')"

say "E2：chat {tools:[query]} 模型侧收敛（llm-calls 证据）"
SID="e2e-tw-$(date +%s)"
BEFORE=$(calls_count "$SVC_NAME" "$SID")
FRAMES=$(chat_sse "$SVC_NAME" "$SID" "你好，简要介绍一下你自己" '["query"]')
if echo "$FRAMES" | grep -q '"type":"error"'; then bad "2.0 chat 报错: $(echo "$FRAMES" | head -c 200)"; else ok "2.0 chat 正常"; fi
TN=$(new_calls "$SVC_NAME" "$SID" "$BEFORE" | last_call_tool_names)
assert_contains "2.1 模型侧含白名单内 query" "$TN" "query"
assert_contains "2.2 模型侧内置工具恒保留" "$TN" "read_file"
assert_not_contains "2.3 名单外 MCP search 被剔除" "$TN" "search"
assert_not_contains "2.4 名单外自定义 echo 被剔除" "$TN" "echo"
assert_not_contains "2.5 名单外自定义 get_current_time 被剔除" "$TN" "get_current_time"

say "E3：下一轮不传 tools → 恢复全部（仅本轮生效）"
BEFORE=$(calls_count "$SVC_NAME" "$SID")
chat_sse "$SVC_NAME" "$SID" "继续，简要回答" ""
TN=$(new_calls "$SVC_NAME" "$SID" "$BEFORE" | last_call_tool_names)
assert_contains "3.1 未传 = MCP query 恢复" "$TN" "query"
assert_contains "3.2 未传 = MCP search 恢复" "$TN" "search"
assert_contains "3.3 未传 = 自定义 echo 恢复" "$TN" "echo"
BEFORE=$(calls_count "$SVC_NAME" "$SID")
chat_sse "$SVC_NAME" "$SID" "再来一次，简要回答" '["search"]'
TN=$(new_calls "$SVC_NAME" "$SID" "$BEFORE" | last_call_tool_names)
assert_contains "3.4 再传 search 仍可收敛" "$TN" "search"
assert_not_contains "3.5 query 本轮被剔除" "$TN" "query"

say "E4：chat {tools:[]} = 仅内置"
BEFORE=$(calls_count "$SVC_NAME" "$SID")
chat_sse "$SVC_NAME" "$SID" "空列表轮次，简要回答" '[]'
TN=$(new_calls "$SVC_NAME" "$SID" "$BEFORE" | last_call_tool_names)
assert_contains "4.1 内置工具恒保留" "$TN" "read_file"
assert_not_contains "4.2 MCP query 被剔除" "$TN" "query"
assert_not_contains "4.3 MCP search 被剔除" "$TN" "search"
assert_not_contains "4.4 自定义 echo 被剔除" "$TN" "echo"

say "E5：全名别名 / 内置名入名单 / 未知名"
BEFORE=$(calls_count "$SVC_NAME" "$SID")
chat_sse "$SVC_NAME" "$SID" "全名轮次，简要回答" '["mcp__tools-a__query"]'
TN=$(new_calls "$SVC_NAME" "$SID" "$BEFORE" | last_call_tool_names)
assert_contains "5.1 全名别名归一为裸名 query" "$TN" "query"
assert_not_contains "5.2 名单外 search 仍被剔除" "$TN" "search"
BEFORE=$(calls_count "$SVC_NAME" "$SID")
chat_sse "$SVC_NAME" "$SID" "内置名轮次，简要回答" '["read_file","ghost-tool"]'
TN=$(new_calls "$SVC_NAME" "$SID" "$BEFORE" | last_call_tool_names)
assert_contains "5.3 内置名入名单可见性不变（= 仅内置）" "$TN" "read_file"
assert_not_contains "5.4 未知名不产生任何业务工具" "$TN" "query"

say "E6：白名单外调用兜底 DENY（mock /stats 执行计数 0）"
STATS_BEFORE=$(curl -s "http://127.0.0.1:$MCP_B_PORT/stats" | jq -r '.calls.search // 0')
BEFORE=$(calls_count "$SVC_NAME" "$SID")
FRAMES=$(chat_sse "$SVC_NAME" "$SID" "请务必调用 search 工具查一下 e2e 关键词，然后简短总结" '["query"]')
sleep 2
STATS_AFTER=$(curl -s "http://127.0.0.1:$MCP_B_PORT/stats" | jq -r '.calls.search // 0')
assert_eq "6.1 白名单轮次诱导 search：mock 实际执行计数仍为 0" "$STATS_AFTER" "$STATS_BEFORE"
DENY_MSG=$(new_calls "$SVC_NAME" "$SID" "$BEFORE" | python3 -c "
import json,sys
calls=json.load(sys.stdin)
hit=False
for c in calls:
    for m in (c.get('request') or {}).get('messages') or []:
        if isinstance(m,dict) and 'not in this turn' in str(m.get('content','')):
            hit=True
print('yes' if hit else 'no')")
assert_eq "6.2 模型收到 DENIED 兜底结果（not in this turn's allowed tools）" "$DENY_MSG" "yes"

say "E7：自定义工具白名单 tools:[echo]"
BEFORE=$(calls_count "$SVC_NAME" "$SID")
chat_sse "$SVC_NAME" "$SID" "回显轮次，简要回答" '["echo"]'
TN=$(new_calls "$SVC_NAME" "$SID" "$BEFORE" | last_call_tool_names)
assert_contains "7.1 自定义 echo 可入白名单" "$TN" "echo"
assert_not_contains "7.2 MCP query 被剔除" "$TN" "query"

say "E7B：与 skills 同请求组合（两白名单正交各自生效）"
avail_of() { # avail_of <callsJson> — 本轮 available_skills 块
  python3 -c "
import json,sys,re
calls=json.load(sys.stdin)
out=[]
for c in calls:
    for m in ((c.get('request') or {}).get('messages') or []):
        if isinstance(m,dict) and m.get('role')=='SYSTEM':
            out += re.findall(r'<available_skills>.*?</available_skills>', str(m.get('content','')), re.S)
print('\\n'.join(out))"
}
BEFORE=$(calls_count "$SVC_NAME" "$SID")
chat_sse "$SVC_NAME" "$SID" "组合轮次，简要回答" '["query"]' "debug-user" '["demo-skill"]'
NEWC=$(new_calls "$SVC_NAME" "$SID" "$BEFORE")
TN=$(echo "$NEWC" | last_call_tool_names)
AV=$(echo "$NEWC" | avail_of)
assert_contains "7B.1 组合轮 tools 白名单生效（含 query）" "$TN" "query"
assert_not_contains "7B.2 组合轮 tools 白名单生效（不含 search）" "$TN" "search"
assert_contains "7B.3 组合轮 skills 白名单生效（available_skills 含 demo-skill）" "$AV" "demo-skill"
assert_eq "7B.4 组合轮 available_skills 仅 1 个技能（白名单收敛）" "$(echo "$AV" | grep -c '<name>')" "1"

say "E8：A2A message.metadata.tools 生效"
A2A_SID="e2e-tw-a2a-$(date +%s)"
curl -s --max-time 600 -X POST "$SVC_URL/" -H 'Content-Type: application/json' -d "$(python3 -c "
import json,sys,time
print(json.dumps({'jsonrpc':'2.0','id':1,'method':'message/send','params':{
 'message':{'role':'user','parts':[{'kind':'text','text':'你好，简要回答'}],'kind':'message',
 'messageId':'e2e-tw-'+str(int(time.time())),
 'metadata':{'userId':'debug-user','sessionId':sys.argv[1],'tools':['query']}},
 'configuration':{'blocking':True}}}))" "$A2A_SID")" >/dev/null
TN=$(llm_calls "$SVC_NAME" "$A2A_SID" | turn_tool_names | tail -1)
assert_contains "8.1 A2A 轮次模型侧含 query" "$TN" "query"
assert_not_contains "8.2 A2A 轮次模型侧不含 search" "$TN" "search"

say "E8B：A2A messageId 撞保留键 sessionId（issue #294）"
# 病态用例：messageId 恰为字面量 "sessionId"。修复前 nested Map 覆盖 metadata.sessionId
# → SDK 会话 id 劣化为 Map 字符串，本轮调用不落在预期会话；修复后分组键前缀化
A2A_SID_CLASH="e2e-tw-a2a-clash-$(date +%s)"
curl -s --max-time 600 -X POST "$SVC_URL/" -H 'Content-Type: application/json' -d "$(python3 -c "
import json,sys
print(json.dumps({'jsonrpc':'2.0','id':1,'method':'message/send','params':{
 'message':{'role':'user','parts':[{'kind':'text','text':'你好，简要回答'}],'kind':'message',
 'messageId':'sessionId',
 'metadata':{'userId':'debug-user','sessionId':sys.argv[1],'tools':['query']}},
 'configuration':{'blocking':True}}}))" "$A2A_SID_CLASH")" >/dev/null
assert_eq "8B.1 碰撞轮调用落在预期会话（sessionId 未被覆盖）" \
  "$(llm_calls "$SVC_NAME" "$A2A_SID_CLASH" | jq -r 'if (.calls|length) >= 1 then "yes" else "no" end')" "yes"
TN=$(llm_calls "$SVC_NAME" "$A2A_SID_CLASH" | turn_tool_names | tail -1)
assert_contains "8B.2 碰撞轮模型侧含 query（白名单经前缀分组键生效）" "$TN" "query"
assert_not_contains "8B.3 碰撞轮模型侧不含 search" "$TN" "search"

say "E9：双副本任意副本同口径（无状态）"
DEPLOY_NAME="oaf-$SVC_NAME"
kubectl -n $NS get deploy "$DEPLOY_NAME" >/dev/null 2>&1 || DEPLOY_NAME="$SVC_NAME"
kubectl -n $NS scale deploy "$DEPLOY_NAME" --replicas=2 >/dev/null
kubectl -n $NS rollout status deploy "$DEPLOY_NAME" --timeout=180s >/dev/null
assert_eq "9.1 双副本就绪" "$(kubectl -n $NS get deploy "$DEPLOY_NAME" -o jsonpath='{.status.readyReplicas}')" "2"
mapfile -t PODS < <(kubectl -n $NS get pod -l "app.kubernetes.io/name=oaf-$SVC_NAME" \
  -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>/dev/null)
if [ ${#PODS[@]} -lt 2 ]; then
  PODS=($(kubectl -n $NS get pod -l "app.kubernetes.io/name=$SVC_NAME" \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>/dev/null))
fi
if [ ${#PODS[@]} -ge 2 ]; then
  kubectl -n $NS port-forward "pod/${PODS[0]}" 18112:8100 >/dev/null 2>&1 &
  PF_A=$!
  kubectl -n $NS port-forward "pod/${PODS[1]}" 18113:8100 >/dev/null 2>&1 &
  PF_B=$!
  ready=0
  for _ in $(seq 1 20); do
    curl -s --max-time 2 http://127.0.0.1:18112/health >/dev/null 2>&1 \
      && curl -s --max-time 2 http://127.0.0.1:18113/health >/dev/null 2>&1 \
      && { ready=1; break; }
    sleep 1
  done
  chat_sse_pod() { # chat_sse_pod <port> <session> <msg> <toolsJson>
    python3 - "$1" "$2" "$3" "$4" <<'PY'
import json, sys, urllib.request
port, sid, msg, tools = sys.argv[1:5]
body = {"message": msg, "sessionId": sid, "userId": "debug-user"}
if tools != "":
    body["tools"] = json.loads(tools)
req = urllib.request.Request("http://127.0.0.1:%s/threads/chat" % port,
    data=json.dumps(body).encode(),
    headers={"Content-Type": "application/json", "Accept": "text/event-stream"})
try:
    with urllib.request.urlopen(req, timeout=600) as resp:
        for raw in resp: pass
except Exception as e:
    print("STREAM_ERROR: %s" % e)
PY
  }
  if [ "$ready" == "1" ]; then
    SID_MR="e2e-tw-mr-$(date +%s)"
    chat_sse_pod 18112 "$SID_MR" "副本 A 轮次，简要回答" '["query"]'
    TN_A=$(curl -s --max-time 10 "http://127.0.0.1:18112/threads/$SID_MR/llm-calls" | turn_tool_names | tail -1)
    assert_contains "9.2 副本 A 含 query" "$TN_A" "query"
    assert_not_contains "9.3 副本 A 不含 search" "$TN_A" "search"
    chat_sse_pod 18113 "$SID_MR" "副本 B 轮次，简要回答" '["search"]'
    TN_B=$(curl -s --max-time 10 "http://127.0.0.1:18113/threads/$SID_MR/llm-calls" | turn_tool_names | tail -1)
    assert_contains "9.4 副本 B 含 search" "$TN_B" "search"
    assert_not_contains "9.5 副本 B 不含 query" "$TN_B" "query"
  else
    bad "9.2 副本 port-forward 未就绪"
  fi
  kill $PF_A $PF_B 2>/dev/null
  kubectl -n $NS scale deploy "$DEPLOY_NAME" --replicas=1 >/dev/null
  kubectl -n $NS rollout status deploy "$DEPLOY_NAME" --timeout=180s >/dev/null
else
  bad "9.2 未取到两个副本 Pod"
fi

say "结果"
echo "  PASS=$PASS FAIL=$FAIL"
if [ "${FAILED_CASES[*]:-}" != "" ]; then
  printf '  \033[31mFAILED:\033[0m\n'
  printf '    - %s\n' "${FAILED_CASES[@]}"
  exit 1
fi
echo "  ALL GREEN"

#!/usr/bin/env bash
# E2E：会话级技能列表（docs/design/session-skill-list-design.md）
# 场景：
#   P  构造并发布含 3 个技能的配置包（无任何角色声明文件）
#   1  只读视图：/skills、/skills?skills=、/skills/available、/skills/parse-refs
#   2  chat {skills:[support-only]}：模型侧 <available_skills> 仅该技能（llm-calls 证据）
#   3  下一轮不传 skills：恢复全部技能（仅本轮生效，不落库）
#   4  chat {skills:[]}：模型无技能；未知名混入：忽略且不报错
#   5  /Skill：名单外不注入（保留原文），名单内注入
#   6  禁用技能：即使列入本轮名单也被剔除（PVC 直写 .skill-states.json）
#   7  A2A message.metadata.skills 生效（模型侧同口径）
#   7B A2A messageId 撞保留键 sessionId（issue #294：分组键前缀化，会话路由不劣化）
#   8  双副本：任意副本续聊同口径（无状态）
#   9  沙箱档（SANDBOX=1）：skills 过滤在沙箱模式成立
# 前置：platform-backend running；新 agent-framework:latest 镜像已导入 kind 节点
# 用法：BASE/A2A_BASE/RUNTIME_IMAGE 可覆盖；SANDBOX=1 追加沙箱档
set -u
BASE="${BASE:-http://localhost:30080/api/v1}"
A2A_BASE="${A2A_BASE:-http://localhost:30080/agent}"
NS=agent-platform
SVC_NAME="${SVC_NAME:-e2e-session-skills}"
SVC_SANDBOX_NAME="${SVC_SANDBOX_NAME:-e2e-session-skills-sbx}"
PKG_NAME="Session Skills E2E"
PASS=0; FAIL=0
declare -a FAILED_CASES

say()  { echo -e "\n\033[1;34m== $* ==\033[0m"; }
ok()   { PASS=$((PASS+1)); echo "  \033[32mPASS\033[0m $1"; }
bad()  { FAIL=$((FAIL+1)); FAILED_CASES+=("$1"); echo "  \033[31mFAIL\033[0m $1"; }
assert_eq() { if [ "$2" == "$3" ]; then ok "$1"; else bad "$1 (got: $2, want: $3)"; fi; }
assert_contains() { case "$2" in *"$3"*) ok "$1";; *) bad "$1 (missing: $3)";; esac; }
assert_not_contains() { case "$2" in *"$3"*) bad "$1 (should NOT contain: $3)";; *) ok "$1";; esac; }

# LLM 配置：显式环境变量优先（CI/本地可覆盖），否则回退 .env.secrets
if [ -z "${LLM_API_KEY:-}" ] || [ -z "${LLM_MODEL:-}" ] || [ -z "${LLM_ENDPOINT:-}" ]; then
  source "$(dirname "$0")/../.env.secrets" 2>/dev/null || source /root/agent-manager/.env.secrets
fi
export LLM_API_KEY LLM_MODEL LLM_ENDPOINT
SANDBOX_SERVER_URL="${OPENSANDBOX_SERVER_URL:-192.168.31.155:8090}"

api() { # api <method> <path> [json-body]
  local m=$1 p=$2 b=${3:-}
  if [ -n "$b" ]; then
    curl -s -X "$m" "$BASE$p" -H 'Content-Type: application/json' -d "$b"
  else
    curl -s -X "$m" "$BASE$p"
  fi
}
upload_zip() { curl -s -X POST "$BASE/packages" -F "file=@$1"; }
wait_status() { # wait_status <id> <want> <timeout_sec>
  local id=$1 want=$2 timeout=${3:-300} waited=0 got=""
  while true; do
    got=$(api GET "/services/$id" | jq -r '.data.status')
    [ "$got" == "$want" ] && return 0
    [ $waited -ge $timeout ] && { echo "  timeout waiting $want (last=$got)"; return 1; }
    sleep 5; waited=$((waited+5))
  done
}
wait_endpoint_json() { # wait_endpoint_json <url> <timeout_sec>
  local url=$1 timeout=${2:-90} waited=0 body=""
  while true; do
    body=$(curl -s --max-time 10 "$url")
    [ -n "$body" ] && echo "$body" | python3 -c "import json,sys; json.load(sys.stdin)" 2>/dev/null && { echo "$body"; return 0; }
    [ $waited -ge $timeout ] && { echo ""; return 1; }
    sleep 3; waited=$((waited+3))
  done
}

# ---------- PVC 直写（kind 节点 local-path-provisioner 路径，与 skills-dynamic-e2e 同款） ----------
PVC_UID=$(kubectl -n $NS get pvc platform-data -o jsonpath='{.spec.volumeName}')
NODE_PVC="/var/local-path-provisioner/${PVC_UID}_agent-platform_platform-data"
pvc_write() { # pvc_write <pkgId> <relpath> <content>
  local rel="$1/$2"
  printf '%s' "$3" | docker exec -i agent-manager-control-plane sh -c \
    "mkdir -p '$NODE_PVC/packages/$(dirname "$rel")' && cat > '$NODE_PVC/packages/$rel' \
     && chmod -R 755 '$NODE_PVC/packages/$(dirname "$rel")' && chmod 644 '$NODE_PVC/packages/$rel' \
     && chown -R 10001:10001 '$NODE_PVC/packages/$1'"
}

# ---------- 请求与断言工具 ----------
chat_sse() { # chat_sse <svc> <sessionId> <message> [skillsJsonOrEmpty] [userId]
  local svc=$1 sid=$2 msg=$3 skills=${4:-} uid=${5:-debug-user}
  python3 - "$svc" "$sid" "$msg" "$skills" "$uid" "$A2A_BASE" <<'PY'
import json, sys, urllib.request
svc, sid, msg, skills, uid, base = sys.argv[1:7]
body = {"message": msg, "sessionId": sid, "userId": uid}
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
# 本轮 <available_skills> 块文本：模型侧技能可见性的唯一干净信号
# （历史轮次/agent 记忆摘要会引用旧技能名与正文标记，不能对整段 request 断言）
turn_avail_text() {
  python3 -c "
import json,sys,re
calls=json.load(sys.stdin)
out=[]
for c in calls:
    req=c.get('request') or {}
    for m in (req.get('messages') or []):
        if isinstance(m,dict) and m.get('role')=='SYSTEM':
            txt=str(m.get('content',''))
            out += re.findall(r'<available_skills>.*?</available_skills>', txt, re.S)
print('\\n'.join(out))"
}
# 本轮用户消息文本（/Skill 注入落点）：每轮调用中最后一条 USER 消息
turn_user_text() {
  python3 -c "
import json,sys
calls=json.load(sys.stdin)
out=[]
for c in calls:
    req=c.get('request') or {}
    last=None
    for m in (req.get('messages') or []):
        if isinstance(m,dict) and m.get('role')=='USER':
            last=m
    if last is not None:
        out.append(str(last.get('content','')))
print('\\n'.join(out))"
}
new_calls() { # new_calls <svc> <sid> <before>
  llm_calls "$1" "$2" | jq -c --argjson n "$3" '.calls[$n:]' 2>/dev/null
}
chat_and_assert() { # 可见性断言：<available_skills> 块
  local case_name=$1 svc=$2 sid=$3 msg=$4 skills=$5 must=$6 not=$7
  local frames calls before avail
  before=$(calls_count "$svc" "$sid")
  frames=$(chat_sse "$svc" "$sid" "$msg" "$skills")
  if echo "$frames" | grep -q '"type":"error"'; then
    bad "$case_name (chat error: $(echo "$frames" | head -c 200))"; return 1
  fi
  calls=$(new_calls "$svc" "$sid" "$before")
  avail=$(echo "$calls" | turn_avail_text)
  [ -n "$must" ] && assert_contains "$case_name（available_skills 含 $must）" "$avail" "$must"
  [ -n "$not" ] && assert_not_contains "$case_name（available_skills 不含 $not）" "$avail" "$not"
}
chat_and_assert_injection() { # /Skill 注入断言：本轮用户消息
  local case_name=$1 svc=$2 sid=$3 msg=$4 skills=$5 must=$6 not=$7
  local frames calls before user
  before=$(calls_count "$svc" "$sid")
  frames=$(chat_sse "$svc" "$sid" "$msg" "$skills")
  if echo "$frames" | grep -q '"type":"error"'; then
    bad "$case_name (chat error: $(echo "$frames" | head -c 200))"; return 1
  fi
  calls=$(new_calls "$svc" "$sid" "$before")
  user=$(echo "$calls" | turn_user_text)
  [ -n "$must" ] && assert_contains "$case_name（用户消息含 $must）" "$user" "$must"
  [ -n "$not" ] && assert_not_contains "$case_name（用户消息不含 $not）" "$user" "$not"
}
a2a_chat_with_skills() { # a2a_chat_with_skills <svc> <sid> <skillsJson>
  curl -s --max-time 600 -X POST "$A2A_BASE/$1/" -H 'Content-Type: application/json' -d "$(python3 -c "
import json,sys,time
print(json.dumps({'jsonrpc':'2.0','id':1,'method':'message/send','params':{
 'message':{'role':'user','parts':[{'kind':'text','text':'你好，简要回答'}],'kind':'message',
 'messageId':'e2e-sk-'+str(int(time.time())),
 'metadata':{'userId':'debug-user','sessionId':sys.argv[1],'skills':json.loads(sys.argv[2])}},
 'configuration':{'blocking':True}}}))" "$2" "$3")" >/dev/null
}
a2a_chat_with_reserved_key_message_id() { # a2a_chat_with_reserved_key_message_id <svc> <sid> <skillsJson>
  # issue #294 病态用例：messageId 恰为字面量 "sessionId"（保留键键名）。
  # 修复前 nested Map 覆盖 metadata.sessionId → SDK 会话 id 劣化为 Map 字符串；
  # 修复后 Controller 分组键前缀化（_agentscope_group_sessionId），会话路由不劣化
  curl -s --max-time 600 -X POST "$A2A_BASE/$1/" -H 'Content-Type: application/json' -d "$(python3 -c "
import json,sys
print(json.dumps({'jsonrpc':'2.0','id':1,'method':'message/send','params':{
 'message':{'role':'user','parts':[{'kind':'text','text':'你好，简要回答'}],'kind':'message',
 'messageId':'sessionId',
 'metadata':{'userId':'debug-user','sessionId':sys.argv[1],'skills':json.loads(sys.argv[2])}},
 'configuration':{'blocking':True}}}))" "$2" "$3")" >/dev/null
}

runtime_env() { # runtime_env [sandbox:true|false]
  local ckpt="jdbc:mysql://oaf-mysql.$NS.svc.cluster.local:3306/oaf_checkpoint?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
  local redis="redis://oaf-redis.$NS.svc.cluster.local:6379"
  if [ "${1:-false}" == "true" ]; then
    local sbx_key=${OPENSANDBOX_API_KEY:-$(kubectl -n $NS get cm oaf-release-agent-env -o jsonpath='{.data.OPENSANDBOX_API_KEY}' 2>/dev/null)}
    CKPT_URL="$ckpt" REDIS_URL="$redis" SBX_KEY="$sbx_key" python3 -c "
import json,os
env={'LLM_API_KEY':os.environ['LLM_API_KEY'],'LLM_MODEL_ID':os.environ['LLM_MODEL'],
 'LLM_BASE_URL':os.environ['LLM_ENDPOINT'],'CHECKPOINT_JDBC_URL':os.environ['CKPT_URL'],
 'CHECKPOINT_USERNAME':'oaf','CHECKPOINT_PASSWORD':'OafPlatform2026',
 'AGENT_REDIS_URL':os.environ['REDIS_URL'],
 'SANDBOX_ENABLED':'true','OPENSANDBOX_SERVER_URL':os.environ['SANDBOX_SERVER_URL']}
if os.environ.get('SBX_KEY'): env['OPENSANDBOX_API_KEY']=os.environ['SBX_KEY']
print(json.dumps(env))"
  else
    CKPT_URL="$ckpt" REDIS_URL="$redis" python3 -c "
import json,os
print(json.dumps({'LLM_API_KEY':os.environ['LLM_API_KEY'],'LLM_MODEL_ID':os.environ['LLM_MODEL'],
 'LLM_BASE_URL':os.environ['LLM_ENDPOINT'],'CHECKPOINT_JDBC_URL':os.environ['CKPT_URL'],
 'CHECKPOINT_USERNAME':'oaf','CHECKPOINT_PASSWORD':'OafPlatform2026',
 'AGENT_REDIS_URL':os.environ['REDIS_URL']}))"
  fi
}

cleanup_mine() {
  local n
  for n in "$SVC_NAME" "$SVC_SANDBOX_NAME"; do
    local id
    # 服务列表字段是 k8sName/shortName/displayName（console 改造后无 name 字段）
    id=$(api GET "/services" | jq -r --arg n "$n" '.data[]? | select(
        (.k8sName // "") == ("oaf-" + $n) or (.shortName // "") == $n
        or (.displayName // "") == $n or ((.k8sName // "") | endswith($n))) | .id' | head -1)
    [ -n "$id" ] && [ "$id" != "null" ] && api DELETE "/services/$id" >/dev/null && echo "  cleaned old service $n (id=$id)"
  done
  # 包名唯一（duplicate_name 409）：清理同名历史包，保证脚本可重复执行
  local pid
  pid=$(api GET "/packages" | jq -r --arg n "$PKG_NAME" '.data[]? | select(.name==$n) | .id' | head -1)
  [ -n "$pid" ] && [ "$pid" != "null" ] && api DELETE "/packages/$pid" >/dev/null && echo "  cleaned old package (id=$pid)"
}

AGENTS_MD='---
name: "Session Skills E2E"
vendorKey: "e2e"
agentKey: "session-skills"
version: "1.0.0"
slug: "e2e/session-skills"
description: "Session skill list E2E agent"
author: "@e2e"
license: "MIT"

skills:
  - name: "support-only"
    source: "local"
    version: "1.0.0"
  - name: "ops-only"
    source: "local"
    version: "1.0.0"
  - name: "common-format"
    source: "local"
    version: "1.0.0"
---

# Session Skills E2E Agent

You are a test agent for OAF session skill list E2E. Answer briefly.
'

skill_md() { # skill_md <name> <description> <body-marker>
  printf -- '---\nname: %s\ndescription: %s\nversion: 1.0.0\n---\n\n# %s\n\nBody marker: %s\n' \
    "$1" "$2" "$1" "$3"
}

###############################################################################
say "P：构造并发布含 3 个技能的配置包（无角色声明文件）"
cleanup_mine
WORKDIR=$(mktemp -d)
printf '%s' "$AGENTS_MD" > "$WORKDIR/AGENTS.md"
mkdir -p "$WORKDIR/skills/support-only" "$WORKDIR/skills/ops-only" "$WORKDIR/skills/common-format"
skill_md "support-only" "客服专用技能" "SUPPORT_ONLY_BODY_7f3a" > "$WORKDIR/skills/support-only/SKILL.md"
skill_md "ops-only" "运维专用技能" "OPS_ONLY_BODY_9d2c" > "$WORKDIR/skills/ops-only/SKILL.md"
skill_md "common-format" "共享技能" "COMMON_FORMAT_BODY_4e8b" > "$WORKDIR/skills/common-format/SKILL.md"
ZIPFILE="$WORKDIR/session-skills-agent.zip"
(cd "$WORKDIR" && zip -qr "$ZIPFILE" AGENTS.md skills)
UP=$(upload_zip "$ZIPFILE")
PKG_ID=$(echo "$UP" | jq -r '.data.id')
[ -n "$PKG_ID" ] && [ "$PKG_ID" != "null" ] && ok "P1 上传配置包 (packageId=$PKG_ID)" || { bad "P1 上传失败: $UP"; exit 1; }

# 镜像不写死：优先可变 latest，其次本地 registry；RUNTIME_IMAGE 可覆盖
RUNTIME_IMAGE="${RUNTIME_IMAGE:-$(api GET /images | jq -r '[.data[].Image | select(. != null and contains(":latest"))][0] // [.data[].Image | select(. != null and contains("5001"))][0] // .data[0].Image // empty')}"
PUB=$(api POST /services "{\"packageId\":$PKG_ID,\"image\":\"$RUNTIME_IMAGE\",\"env\":$(runtime_env false)}")
SVC_ID=$(echo "$PUB" | jq -r '.data.id')
[ -n "$SVC_ID" ] && [ "$SVC_ID" != "null" ] && ok "P2 发布受理 (svcId=$SVC_ID, image=$RUNTIME_IMAGE)" || { bad "P2 发布失败: $PUB"; exit 1; }
if wait_status "$SVC_ID" running 300; then ok "P3 服务 running"; else bad "P3 未达 running"; exit 1; fi
SVC_URL="$A2A_BASE/$SVC_NAME"
wait_endpoint_json "$SVC_URL/skills" 120 >/dev/null || { bad "P4 服务端点未就绪"; exit 1; }

say "E1：只读视图（缺省=全部，?skills= 白名单）"
ALL_SKILLS=$(curl -s "$SVC_URL/skills")
assert_eq "1.1 缺省返回全部 3 个技能" "$(echo "$ALL_SKILLS" | jq -r 'length')" "3"
ONLY_SUPPORT=$(curl -s "$SVC_URL/skills?skills=support-only")
assert_eq "1.2 ?skills=support-only 只返回 1 个" "$(echo "$ONLY_SUPPORT" | jq -r 'length')" "1"
assert_contains "1.3 其中为 support-only" "$ONLY_SUPPORT" '"name":"support-only"'
UNKNOWN_MIX=$(curl -s "$SVC_URL/skills?skills=support-only,ghost")
assert_eq "1.4 未知名混入被忽略" "$(echo "$UNKNOWN_MIX" | jq -r 'length')" "1"
AVAIL=$(curl -s "$SVC_URL/skills/available?skills=support-only")
assert_contains "1.5 available 白名单" "$AVAIL" '"name":"support-only"'
assert_not_contains "1.6 available 不含名单外" "$AVAIL" '"name":"ops-only"'
PARSE=$(curl -s --get "$SVC_URL/skills/parse-refs" --data-urlencode "message=/support-only /ops-only" --data-urlencode "skills=support-only")
assert_eq "1.7 parse-refs 白名单" "$(echo "$PARSE" | jq -r '.skills | join(",")')" "support-only"

say "E2：chat {skills:[support-only]} 模型侧收敛"
SID="e2e-sk-$(date +%s)"
chat_and_assert "2.1 本轮仅见 support-only" "$SVC_NAME" "$SID" "你好，简要介绍一下你自己" '["support-only"]' "support-only" "ops-only"

say "E3：下一轮不传 skills → 恢复全部（仅本轮生效）"
chat_and_assert "3.1 未传 = 全部技能" "$SVC_NAME" "$SID" "继续，简要回答" "" "ops-only" ""
chat_and_assert "3.2 再传仍可收敛" "$SVC_NAME" "$SID" "再来一次，简要回答" '["ops-only"]' "ops-only" "support-only"
THREAD_JSON=$(curl -s "$A2A_BASE/$SVC_NAME/threads/$SID")
assert_eq "3.3 GET /threads/{sid} 无 skills 持久化字段" "$(echo "$THREAD_JSON" | jq -r 'has("skills")')" "false"

say "E4：空列表与未知名"
BEFORE_EMPTY=$(calls_count "$SVC_NAME" "$SID")
chat_and_assert "4.1 [] = 模型无技能" "$SVC_NAME" "$SID" "空列表轮次，简要回答" '[]' "" "support-only"
EMPTY_CALLS=$(new_calls "$SVC_NAME" "$SID" "$BEFORE_EMPTY" 2>/dev/null)
EMPTY_AVAIL=$(echo "$EMPTY_CALLS" | turn_avail_text)
assert_not_contains "4.2 [] 轮次 available_skills 为空" "$EMPTY_AVAIL" "<name>"
chat_and_assert "4.3 未知名混入忽略且不报错" "$SVC_NAME" "$SID" "未知名轮次，简要回答" '["support-only","ghost-skill"]' "support-only" "ops-only"

say "E5：/Skill 注入与名单一致"
chat_and_assert_injection "5.1 名单外 /ops-only 不注入（保留原文）" "$SVC_NAME" "$SID" "/ops-only 这个技能的内容是什么" '["support-only"]' "" "OPS_ONLY_BODY_9d2c"
chat_and_assert_injection "5.2 名单内 /ops-only 注入" "$SVC_NAME" "$SID" "/ops-only 这个技能的内容是什么" '["ops-only"]' "OPS_ONLY_BODY_9d2c" ""

say "E6：禁用技能即使列入名单也被剔除"
# /config 为只读 PVC：toggle API 不可写，按 L2 状态文件直写（.skill-states.json = 禁用名 JSON 数组）
pvc_write "$PKG_ID" "skills/.skill-states.json" '["ops-only"]' && ok "6.1 PVC 写入禁用状态" || bad "6.1 PVC 写入失败"
sleep 3
DISABLED_VIEW=$(curl -s "$SVC_URL/skills?skills=ops-only")
assert_eq "6.2 视图剔除禁用技能" "$(echo "$DISABLED_VIEW" | jq -r 'length')" "0"
chat_and_assert "6.3 模型侧名单内禁用技能被剔除" "$SVC_NAME" "$SID" "禁用后轮次，简要回答" '["ops-only"]' "" "ops-only"
pvc_write "$PKG_ID" "skills/.skill-states.json" '[]' >/dev/null

say "E7：A2A message.metadata.skills 生效"
A2A_SID="e2e-sk-a2a-$(date +%s)"
BEFORE_A2A=$(calls_count "$SVC_NAME" "$A2A_SID")
a2a_chat_with_skills "$SVC_NAME" "$A2A_SID" '["support-only"]'
CALLS_A2A=$(new_calls "$SVC_NAME" "$A2A_SID" "$BEFORE_A2A")
AVAIL_A2A=$(echo "$CALLS_A2A" | turn_avail_text)
assert_contains "7.1 A2A 轮次 available_skills 含 support-only" "$AVAIL_A2A" "support-only"
assert_not_contains "7.2 A2A 轮次 available_skills 不含 ops-only" "$AVAIL_A2A" "ops-only"

say "E7B：A2A messageId 撞保留键 sessionId（issue #294）"
A2A_SID_COLLIDE="e2e-sk-a2a-clash-$(date +%s)"
BEFORE_COLLIDE=$(calls_count "$SVC_NAME" "$A2A_SID_COLLIDE")
a2a_chat_with_reserved_key_message_id "$SVC_NAME" "$A2A_SID_COLLIDE" '["support-only"]'
NEW_COLLIDE=$(new_calls "$SVC_NAME" "$A2A_SID_COLLIDE" "$BEFORE_COLLIDE")
# 修复前 metadata.sessionId 被分组 Map 覆盖 → SDK 会话 id 劣化，本轮调用不落在预期会话
assert_eq "7B.1 碰撞轮调用落在预期会话（sessionId 未被覆盖）" \
  "$(echo "$NEW_COLLIDE" | jq -r 'if length >= 1 then "yes" else "no" end')" "yes"
AVAIL_COLLIDE=$(echo "$NEW_COLLIDE" | turn_avail_text)
assert_contains "7B.2 碰撞轮 available_skills 含 support-only（白名单经前缀分组键生效）" "$AVAIL_COLLIDE" "support-only"
assert_not_contains "7B.3 碰撞轮 available_skills 不含 ops-only" "$AVAIL_COLLIDE" "ops-only"

say "E8：双副本任意副本同口径（无状态）"
DEPLOY_NAME="oaf-$SVC_NAME"
kubectl -n $NS get deploy "$DEPLOY_NAME" >/dev/null 2>&1 || DEPLOY_NAME="$SVC_NAME"
kubectl -n $NS scale deploy "$DEPLOY_NAME" --replicas=2 >/dev/null
kubectl -n $NS rollout status deploy "$DEPLOY_NAME" --timeout=180s >/dev/null
assert_eq "8.1 双副本就绪" "$(kubectl -n $NS get deploy "$DEPLOY_NAME" -o jsonpath='{.status.readyReplicas}')" "2"
mapfile -t PODS < <(kubectl -n $NS get pod -l "app.kubernetes.io/name=oaf-$SVC_NAME" \
  -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>/dev/null)
if [ ${#PODS[@]} -lt 2 ]; then
  PODS=($(kubectl -n $NS get pod -l "app.kubernetes.io/name=$SVC_NAME" \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>/dev/null))
fi
if [ ${#PODS[@]} -ge 2 ]; then
  kubectl -n $NS port-forward "pod/${PODS[0]}" 18110:8100 >/dev/null 2>&1 &
  PF_A=$!
  kubectl -n $NS port-forward "pod/${PODS[1]}" 18111:8100 >/dev/null 2>&1 &
  PF_B=$!
  ready=0
  for _ in $(seq 1 20); do
    curl -s --max-time 2 http://127.0.0.1:18110/health >/dev/null 2>&1 \
      && curl -s --max-time 2 http://127.0.0.1:18111/health >/dev/null 2>&1 \
      && { ready=1; break; }
    sleep 1
  done
  if [ "$ready" == "1" ]; then
    chat_sse_pod() { # chat_sse_pod <port> <session> <msg> <skillsJson>
      python3 - "$1" "$2" "$3" "$4" <<'PY'
import json, sys, urllib.request
port, sid, msg, skills = sys.argv[1:5]
body = {"message": msg, "sessionId": sid, "userId": "debug-user"}
if skills != "":
    body["skills"] = json.loads(skills)
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
    SID_MR="e2e-sk-mr-$(date +%s)"
    # 副本 A：skills=[support-only]
    chat_sse_pod 18110 "$SID_MR" "副本 A 轮次，简要回答" '["support-only"]'
    CALLS_A=$(curl -s --max-time 10 "http://127.0.0.1:18110/threads/$SID_MR/llm-calls" | jq -c '.calls')
    AVAIL_A=$(echo "$CALLS_A" | turn_avail_text)
    assert_contains "8.2 副本 A available_skills 含 support-only" "$AVAIL_A" "support-only"
    assert_not_contains "8.3 副本 A available_skills 不含 ops-only" "$AVAIL_A" "ops-only"
    # 副本 B：同一会话 skills=[ops-only]（无状态：各轮独立，任意副本一致）
    BEFORE_B=$(curl -s --max-time 10 "http://127.0.0.1:18111/threads/$SID_MR/llm-calls" | jq -r '.calls | length' 2>/dev/null || echo 0)
    chat_sse_pod 18111 "$SID_MR" "副本 B 轮次，简要回答" '["ops-only"]'
    CALLS_B=$(curl -s --max-time 10 "http://127.0.0.1:18111/threads/$SID_MR/llm-calls" | jq -c --argjson n "$BEFORE_B" '.calls[$n:]')
    AVAIL_B=$(echo "$CALLS_B" | turn_avail_text)
    assert_contains "8.4 副本 B available_skills 含 ops-only" "$AVAIL_B" "ops-only"
    assert_not_contains "8.5 副本 B available_skills 不含 support-only" "$AVAIL_B" "support-only"
  else
    bad "8.2 副本 port-forward 未就绪"
  fi
  kill $PF_A $PF_B 2>/dev/null
  kubectl -n $NS scale deploy "$DEPLOY_NAME" --replicas=1 >/dev/null
  kubectl -n $NS rollout status deploy "$DEPLOY_NAME" --timeout=180s >/dev/null
else
  bad "8.2 未取到两个副本 Pod"
fi

if [ "${SANDBOX:-0}" == "1" ]; then
  say "E9：沙箱档——skills 过滤在沙箱模式成立（文件可见性为已知边界）"
  PUB_SBX=$(api POST /services "{\"packageId\":$PKG_ID,\"image\":\"$RUNTIME_IMAGE\",\"name\":\"$SVC_SANDBOX_NAME\",\"env\":$(runtime_env true)}")
  SVC_SBX_ID=$(echo "$PUB_SBX" | jq -r '.data.id')
  if [ -z "$SVC_SBX_ID" ] || [ "$SVC_SBX_ID" == "null" ]; then
    bad "9.1 沙箱服务发布失败: $PUB_SBX"
  elif wait_status "$SVC_SBX_ID" running 420; then
    ok "9.1 沙箱服务 running"
    SBX_SID="e2e-sk-sbx-$(date +%s)"
    chat_and_assert "9.2 沙箱轮次仅见名单内技能" "$SVC_SANDBOX_NAME" "$SBX_SID" "你好，简要介绍一下自己" '["support-only"]' "support-only" "ops-only"
  else
    bad "9.1 沙箱服务未达 running"
  fi
fi

say "结果"
echo "  PASS=$PASS FAIL=$FAIL"
if [ "${FAILED_CASES[*]:-}" != "" ]; then
  printf '  \033[31mFAILED:\033[0m\n'
  printf '    - %s\n' "${FAILED_CASES[@]}"
  exit 1
fi
echo "  ALL GREEN"

#!/usr/bin/env bash
# E2E：用户技能管理（L4 个人覆盖）——管理接口改技能 → 真实 A2A 会话生效性
#
# 覆盖场景（自建包 + 自建服务，脚本自己清理）：
#   P  构造带 skills/demo-a 的包并发布服务
#   1  管理接口 PUT /skills/users/{uid}/{name}（写个人覆盖）→ action/version
#   2  GET 明细 source=user / hasUserOverride=true / hasPackageBaseline=true
#   3  索引：GET /skills/users 与 /debug/user-skills 均列出该 userId
#   4  A2A：该用户读到个人覆盖 marker；另一个用户仍读到包内 marker（用户隔离）
#   5  DELETE → GET 回落 source=package（内容与包内逐字节一致）；A2A 新会话回落包内 marker
#   6  POST sync-from-package → source=user 且内容与包内逐字节一致（含 scripts/ 资源）
#   7  负例：非法 userId/name 400、不存在 404、>100KB 413
#   8  沙箱档（SANDBOX=1）会话内 skill_manage 写入被回写进 agent_fs（E8）
#   9  沙箱档回写仲裁：DELETE 的 tombstone 防「删除被复活」、PUT 的 admin-override 防
#      「管理面写入被同代容器内旧副本改回」（E9，需同一 userId 连续两次 call 触发回写）
#
# 前置：platform-backend running；新 agent-framework 镜像已导入 kind 节点并在服务上生效：
#   cd agent-framework && make IMAGE=172.20.0.1:5001/agent-framework:latest docker-build
#   docker save 172.20.0.1:5001/agent-framework:latest | \
#     docker exec -i agent-manager-control-plane ctr --namespace k8s.io images import -
#
# 档位说明：默认（非沙箱）档推理时直接读 agent_fs 的 L4（E1-E7 断言在此档验证）。
#   SANDBOX=1 跑管理面（KV 读写/回落）用例 + E8/E9 沙箱回写用例：沙箱档会话读的是容器内技能副本，
#   管理面写入的 L4 需“会话开始物化 L4”能力才对会话生效（本次未实现），故 A2A 生效性断言
#   仅非沙箱档成立；反向（会话内 skill_manage 写入 → 回写 agent_fs）由 E8 断言，
#   回写仲裁（tombstone 防复活 / admin-override 防管理面写入被改回）由 E9 断言。
set -u
BASE="${BASE:-http://localhost:30080/api/v1}"
NS=agent-platform
SVC_NAME="e2e-user-skill-admin"
SVC_SANDBOX_NAME="e2e-user-skill-admin-sbx"
A2A_BASE="${A2A_BASE:-http://localhost:30080/agent}"
PASS=0; FAIL=0
declare -a FAILED_CASES

say()  { echo -e "\n\033[1;34m== $* ==\033[0m"; }
ok()   { PASS=$((PASS+1)); echo "  \033[32mPASS\033[0m $1"; }
bad()  { FAIL=$((FAIL+1)); FAILED_CASES+=("$1"); echo "  \033[31mFAIL\033[0m $1"; }
assert_eq() { if [ "$2" == "$3" ]; then ok "$1"; else bad "$1 (got: $2, want: $3)"; fi; }
assert_contains() { case "$2" in *"$3"*) ok "$1";; *) bad "$1 (missing: $3, got: $(echo "$2" | head -c 200))";; esac; }
assert_not_contains() { case "$2" in *"$3"*) bad "$1 (should NOT contain: $3)";; *) ok "$1";; esac; }

source "$(dirname "$0")/../.env.secrets" 2>/dev/null || source /root/agent-manager/.env.secrets
export LLM_API_KEY LLM_MODEL LLM_ENDPOINT

api() { # api <method> <path> [json-body] —— 平台 API（:30080/api/v1）
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
  local url=$1 timeout=${2:-60} waited=0 body=""
  while true; do
    body=$(curl -s --max-time 10 "$url")
    [ -n "$body" ] && echo "$body" | python3 -c "import json,sys; json.load(sys.stdin)" 2>/dev/null && { echo "$body"; return 0; }
    [ $waited -ge $timeout ] && { echo ""; return 1; }
    sleep 3; waited=$((waited+3))
  done
}

# ---------- 业务 agent 侧接口（SVC_URL 在发布完成后赋值） ----------
# 发布名/环境：沙箱档用独立名字（k8sName/ingress 路径由 name 派生，避免同 slug 派生名冲突）
if [ "${SANDBOX:-0}" == "1" ]; then PUB_NAME="$SVC_SANDBOX_NAME"; SANDBOX_LEG="true"; else PUB_NAME="$SVC_NAME"; SANDBOX_LEG="false"; fi
svc_url() { echo "$A2A_BASE/$PUB_NAME"; }
svc_body() { # svc_body <method> <path> [json-body]
  local m=$1 p=$2 b=${3:-}
  if [ -n "$b" ]; then
    curl -s -X "$m" "$(svc_url)$p" -H 'Content-Type: application/json' -d "$b"
  else
    curl -s -X "$m" "$(svc_url)$p"
  fi
}
assert_svc_error() { # assert_svc_error <case> <method> <path> <want-status> <want-error> [body-file]
  local case_name=$1 m=$2 p=$3 want_status=$4 want_error=$5 body_file=${6:-} out code payload err
  if [ -n "$body_file" ]; then
    out=$(curl -s -w '\n%{http_code}' -X "$m" "$(svc_url)$p" -H 'Content-Type: application/json' -d @"$body_file")
  else
    out=$(curl -s -w '\n%{http_code}' -X "$m" "$(svc_url)$p")
  fi
  code=$(echo "$out" | tail -1)
  payload=$(echo "$out" | sed '$d')
  err=$(echo "$payload" | jq -r '.error // empty' 2>/dev/null)
  if [ "$code" == "$want_status" ] && [ "$err" == "$want_error" ]; then
    ok "$case_name (http=$code $err)"
  else
    bad "$case_name (http=$code error=$err, want $want_status/$want_error)"
  fi
}

# ---------- A2A 对话（每次独立 sessionId，避免历史串味） ----------
a2a_send() { # a2a_send <svc-name> <userId> <text> → 输出回复全文（超时 600s）
  local svc=$1 uid=$2 text=$3 payload resp
  payload=$(python3 -c "
import json,sys,time
print(json.dumps({'jsonrpc':'2.0','id':1,'method':'message/send','params':{
 'message':{'role':'user','parts':[{'kind':'text','text':sys.argv[1]}],'kind':'message',
 'messageId':'e2e-us-'+str(int(time.time())),'metadata':{'userId':sys.argv[2],'sessionId':sys.argv[3]}},
 }}))" "$text" "$uid" "us-sess-$(date +%s%N)-$RANDOM")
  resp=$(curl -s --max-time 600 -X POST "$A2A_BASE/$svc/" -H 'Content-Type: application/json' -d "$payload")
  [ -z "$resp" ] && { echo "<empty-response>"; return; }
  echo "$resp" | python3 -c "
import json,sys
try: r=json.load(sys.stdin)
except Exception: print('<non-json-response>'); sys.exit(0)
def texts(o,acc):
    if isinstance(o,dict):
        for v in o.values(): texts(v,acc)
    elif isinstance(o,list):
        for v in o: texts(v,acc)
    elif isinstance(o,str): acc.append(o)
acc=[]; texts(r.get('result',r),acc)
print(' '.join(acc))"
}

a2a_assert_marker() { # a2a_assert_marker <case> <svc> <userId> <prompt> <marker>
  local case_name=$1 svc=$2 uid=$3 prompt=$4 marker=$5 reply=""
  for attempt in 1 2; do
    reply=$(a2a_send "$svc" "$uid" "$prompt")
    if [ -n "$reply" ] && [[ "$reply" == *"$marker"* ]]; then
      ok "$case_name"; return 0
    fi
    [ $attempt -eq 1 ] && { echo "    retry after first attempt..."; sleep 5; }
  done
  bad "$case_name (marker missing: $marker) reply=$(echo "$reply" | head -c 200)"
}

CKPT_URL="jdbc:mysql://oaf-mysql.$NS.svc.cluster.local:3306/oaf_checkpoint?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
runtime_env() { # runtime_env [sandbox:true|false]
  if [ "${1:-false}" == "true" ]; then
    SBX_KEY=${OPENSANDBOX_API_KEY:-$(kubectl -n $NS get cm oaf-release-agent-env \
      -o jsonpath='{.data.OPENSANDBOX_API_KEY}' 2>/dev/null)} \
    python3 -c "
import json,os
env={'LLM_API_KEY':os.environ['LLM_API_KEY'],'LLM_MODEL_ID':os.environ['LLM_MODEL'],
 'LLM_BASE_URL':os.environ['LLM_ENDPOINT'],'CHECKPOINT_JDBC_URL':os.environ['CKPT_URL'],
 'CHECKPOINT_USERNAME':'oaf','CHECKPOINT_PASSWORD':'OafPlatform2026',
 'SANDBOX_ENABLED':'true','OPENSANDBOX_SERVER_URL':os.environ['SANDBOX_SERVER_URL']}
if os.environ.get('SBX_KEY'): env['OPENSANDBOX_API_KEY']=os.environ['SBX_KEY']
print(json.dumps(env))"
  else
    python3 -c "
import json,os
print(json.dumps({'LLM_API_KEY':os.environ['LLM_API_KEY'],'LLM_MODEL_ID':os.environ['LLM_MODEL'],
 'LLM_BASE_URL':os.environ['LLM_ENDPOINT'],'CHECKPOINT_JDBC_URL':os.environ['CKPT_URL'],
 'CHECKPOINT_USERNAME':'oaf','CHECKPOINT_PASSWORD':'OafPlatform2026'}))"
  fi
}
SANDBOX_SERVER_URL="${OPENSANDBOX_SERVER_URL:-192.168.31.155:8090}"
export CKPT_URL SANDBOX_SERVER_URL

api_code() { # api_code <method> <path> —— 输出 "<http_code> <body>"（删除必须校验状态码，curl 退出码恒 0）
  local m=$1 p=$2 out
  out=$(curl -s -w '\n%{http_code}' -X "$m" "$BASE$p")
  echo "$(echo "$out" | tail -1) $(echo "$out" | sed '$d')"
}

cleanup_mine() { # 只清理本脚本创建的资源
  for n in "$SVC_NAME" "$SVC_SANDBOX_NAME"; do
    local IDS=" " ID RES
    # 服务实体字段是 k8sName/displayName，没有 name（用 .name 会静默匹配不到 → 清理变空操作，
    # 遗留同名服务会让本次 Publish 派生 k8sName 为 …-2，ingress 路径偏移导致整轮 404）；
    # 按 displayName 匹配（与 e2e/file-support-e2e.sh 同口径）可一并清掉 …-2 派生名遗留
    IDS=$(api GET "/services?keyword=$n" | jq -r --arg n "$n" '.data[]? | select(.displayName==$n) | .id')
    for ID in $IDS; do
      [ -n "$ID" ] && [ "$ID" != "null" ] || continue
      RES=$(api_code DELETE "/services/$ID")
      case "${RES%% *}" in
        2*) echo "  cleaned old service $n (id=$ID)";;
        *)  echo -e "  \033[33mWARN\033[0m 清理遗留服务 $n (id=$ID) 失败: ${RES%% *} ${RES#* }" >&2;;
      esac
    done
  done
}
cleanup_all() {
  local RES
  if [ -n "${SVC_ID:-}" ] && [ "$SVC_ID" != "null" ]; then
    RES=$(api_code DELETE "/services/$SVC_ID")
    case "${RES%% *}" in
      2*) echo "  cleaned service $PUB_NAME (id=$SVC_ID)";;
      *)  echo -e "  \033[33mWARN\033[0m 清理服务 $PUB_NAME (id=$SVC_ID) 失败: ${RES%% *} ${RES#* }" >&2;;
    esac
  fi
  if [ -n "${PKG_ID:-}" ] && [ "$PKG_ID" != "null" ]; then
    RES=$(api_code DELETE "/packages/$PKG_ID")
    case "${RES%% *}" in
      2*) echo "  cleaned package (id=$PKG_ID)";;
      *)  echo -e "  \033[33mWARN\033[0m 清理包 (id=$PKG_ID) 失败: ${RES%% *} ${RES#* }" >&2;;
    esac
  fi
  [ -n "${WORKDIR:-}" ] && rm -rf "$WORKDIR"
}
trap cleanup_all EXIT

USAGE_PROMPT='请读取技能 demo-a 的 SKILL.md，报告文件中 Skill content marker 后面的标记字符串。'
TS=$(date +%s)
UID_A="us-a-$TS"          # 目标用户（写个人覆盖）
UID_B="us-b-$TS"          # 隔离对照用户（不得受影响）
OVERRIDE_MARKER="ADMIN-OVERRIDE-$TS"

PKG_SKILL_MD='---
name: demo-a
description: Demo skill A package baseline
version: 1.0.0
---

# Demo A

Skill content marker: PKG-DEMO-A-4f7c
'
HELLO_SH='#!/bin/sh
echo "hello-from-demo-a"
'
agents_md() {
  cat <<'EOF'
---
name: "User Skill Admin E2E"
vendorKey: "e2e"
agentKey: "user-skill-admin"
version: "1.0.0"
slug: "e2e/user-skill-admin"
description: "User skill (L4) admin API E2E agent"
author: "@e2e"
license: "MIT"

skills:
  - name: "demo-a"
    source: "local"
    version: "1.0.0"
    required: true
---

# User Skill Admin E2E Agent

You are a test agent for OAF user-skill admin API E2E.
When asked to read a skill, use the load_skill_through_path tool with the skill id and path SKILL.md,
then report the exact marker string found in the file content.
EOF
}

###############################################################################
say "P：构造并发布测试包 / 服务"
cleanup_mine
WORKDIR=$(mktemp -d)
agents_md > "$WORKDIR/AGENTS.md"
mkdir -p "$WORKDIR/skills/demo-a/scripts"
printf '%s' "$PKG_SKILL_MD" > "$WORKDIR/skills/demo-a/SKILL.md"
printf '%s' "$HELLO_SH" > "$WORKDIR/skills/demo-a/scripts/hello.sh"
chmod +x "$WORKDIR/skills/demo-a/scripts/hello.sh"
ZIPFILE="$WORKDIR/user-skill-admin.zip"
(cd "$WORKDIR" && zip -qr "$ZIPFILE" AGENTS.md skills)
UP=$(upload_zip "$ZIPFILE")
PKG_ID=$(echo "$UP" | jq -r '.data.id')
[ -n "$PKG_ID" ] && [ "$PKG_ID" != "null" ] && ok "P1 上传测试包 (packageId=$PKG_ID)" \
  || { bad "P1 上传失败: $(echo "$UP" | head -c 200)"; exit 1; }

RUNTIME_IMAGE=$(api GET /images | jq -r '[.data[].Image | select(. != null and contains(":5001"))][0] // .data[0].Image // empty')
PUB=$(api POST /services "{\"packageId\":$PKG_ID,\"name\":\"$PUB_NAME\",\"image\":\"$RUNTIME_IMAGE\",\"env\":$(runtime_env "$SANDBOX_LEG")}")
SVC_ID=$(echo "$PUB" | jq -r '.data.id')
[ -n "$SVC_ID" ] && [ "$SVC_ID" != "null" ] && ok "P2 发布服务受理 (svcId=$SVC_ID)" \
  || { bad "P2 发布失败: $(echo "$PUB" | head -c 200)"; exit 1; }
if wait_status "$SVC_ID" running 300; then ok "P3 服务 running"; else bad "P3 未达 running"; exit 1; fi
SKILLS_JSON=$(wait_endpoint_json "$(svc_url)/skills")
assert_contains "P4 包内基线技能可见" "$SKILLS_JSON" '"name":"demo-a"'

###############################################################################
say "E1：管理接口写入个人覆盖（PUT）"
cat > "$WORKDIR/override.json" <<EOF
---
name: demo-a
description: Demo skill A user override
version: 1.0.1
---

# Demo A

Skill content marker: $OVERRIDE_MARKER
EOF
PUT_BODY=$(python3 -c "
import json,sys
print(json.dumps({'content': open(sys.argv[1]).read()}))" "$WORKDIR/override.json")
PUT_RESP=$(svc_body PUT "/skills/users/$UID_A/demo-a" "$PUT_BODY")
assert_eq "1.1 PUT 新建个人覆盖 action=created" "$(echo "$PUT_RESP" | jq -r '.action')" "created"
PUT_VER=$(echo "$PUT_RESP" | jq -r '.version')
case "$PUT_VER" in ''|null|0) bad "1.2 返回 KV 版本号（got: $PUT_VER）";; *) ok "1.2 返回 KV 版本号 (version=$PUT_VER)";; esac

say "E2：明细视图（GET 个人覆盖）"
GET_RESP=$(svc_body GET "/skills/users/$UID_A/demo-a")
assert_eq "2.1 source=user"        "$(echo "$GET_RESP" | jq -r '.source')" "user"
assert_eq "2.2 hasUserOverride"    "$(echo "$GET_RESP" | jq -r '.hasUserOverride')" "true"
assert_eq "2.3 hasPackageBaseline（删除后有基线可回落）" \
  "$(svc_body GET "/skills/users/$UID_A" | jq -r '.skills[0].hasPackageBaseline')" "true"
assert_contains "2.4 内容为写入的覆盖版本" "$(echo "$GET_RESP" | jq -r '.content')" "$OVERRIDE_MARKER"
PKG_VIEW=$(svc_body GET "/skills/users/$UID_B/demo-a")
assert_eq "2.5 未写入的用户读到的仍是包内基线" "$(echo "$PKG_VIEW" | jq -r '.source')" "package"
assert_eq "2.6 包内同一技能存在（hasPackageBaseline）" \
  "$(svc_body GET "/skills/manage" | jq -r '[.[] | select(.name=="demo-a")] | length')" "1"

say "E3：用户索引"
LIST_SKILLS=$(svc_body GET "/skills/users/$UID_A")
assert_contains "3.1 GET /skills/users/{uid} 列出个人技能" "$(echo "$LIST_SKILLS" | jq -c '.skills[].name')" "demo-a"
assert_eq "3.2 该用户技能条数" "$(echo "$LIST_SKILLS" | jq -r '.skills | length')" "1"
USERS_IDX=$(svc_body GET "/skills/users")
assert_contains "3.3 GET /skills/users 索引含该用户" "$(echo "$USERS_IDX" | jq -c '.users[].userId')" "$UID_A"
DEBUG_IDX=$(svc_body GET "/debug/user-skills")
assert_contains "3.4 /debug/user-skills 索引含该用户" "$(echo "$DEBUG_IDX" | jq -c '.users[].userId')" "$UID_A"
# 共享段消歧（实测口径，勿照直觉改）：/skills/{name}/content（SkillManageController）
# 比 /skills/users/{userId} 更具体，userId 恰为 "content" 时由前者命中；
# 包内无名为 users 的技能 → 404 not_found（该 userId 的明细仍可用 /skills/users 索引 + 写入路径）
assert_svc_error "3.5 歧义路径 /skills/users/content 由包内技能内容路由命中 → 404" \
  GET "/skills/users/content" 404 not_found

###############################################################################
if [ "${SANDBOX:-0}" == "1" ]; then
  say "E4：A2A 生效性（沙箱档跳过——会话读容器内副本，需‘会话开始物化 L4’，本次未实现）"
  echo "  SKIP 沙箱档 A2A 生效性断言（KV 管理面已在上方验证）"
else
  say "E4：A2A 生效性（真实会话读取个人覆盖 / 用户隔离）"
  a2a_assert_marker "4.1 目标用户读到个人覆盖 marker" "$PUB_NAME" "$UID_A" "$USAGE_PROMPT" "$OVERRIDE_MARKER"
  OTHER_REPLY=$(a2a_send "$PUB_NAME" "$UID_B" "$USAGE_PROMPT")
  assert_contains "4.2 其他用户仍读到包内 marker（隔离）" "$OTHER_REPLY" "PKG-DEMO-A-4f7c"
  assert_not_contains "4.3 其他用户看不到个人覆盖 marker" "$OTHER_REPLY" "$OVERRIDE_MARKER"
fi

###############################################################################
say "E5：删除个人覆盖 → 回落包内基线"
DEL_RESP=$(svc_body DELETE "/skills/users/$UID_A/demo-a")
assert_eq "5.1 DELETE 返回删除文件数（SKILL.md）" "$(echo "$DEL_RESP" | jq -r '.deletedFiles')" "1"
FALLBACK=$(svc_body GET "/skills/users/$UID_A/demo-a")
assert_eq "5.2 删除后 source=package" "$(echo "$FALLBACK" | jq -r '.source')" "package"
assert_eq "5.3 删除后 hasUserOverride=false" "$(echo "$FALLBACK" | jq -r '.hasUserOverride')" "false"
echo "$FALLBACK" | jq -jr '.content' > "$WORKDIR/fallback.md"
if cmp -s "$WORKDIR/fallback.md" "$WORKDIR/skills/demo-a/SKILL.md"; then
  ok "5.4 回落内容与包内逐字节一致"
else
  bad "5.4 回落内容与包内不一致 ($(wc -c <"$WORKDIR/fallback.md") vs $(wc -c <"$WORKDIR/skills/demo-a/SKILL.md") bytes)"
fi
assert_eq "5.5 个人技能列表已清空" "$(svc_body GET "/skills/users/$UID_A" | jq -r '.skills | length')" "0"
if [ "${SANDBOX:-0}" != "1" ]; then
  a2a_assert_marker "5.6 A2A 新会话回落包内 marker" "$PUB_NAME" "$UID_A" "$USAGE_PROMPT" "PKG-DEMO-A-4f7c"
fi

###############################################################################
say "E6：从包内下发（sync-from-package，含资源文件）"
SYNC_RESP=$(svc_body POST "/skills/users/$UID_A/demo-a/sync-from-package" '{}')
assert_eq "6.1 下发文件数（SKILL.md + scripts/hello.sh）" "$(echo "$SYNC_RESP" | jq -r '.files | length')" "2"
assert_contains "6.2 下发清单含 scripts/hello.sh" "$(echo "$SYNC_RESP" | jq -c '.files')" "scripts/hello.sh"
SYNC_VIEW=$(svc_body GET "/skills/users/$UID_A/demo-a")
assert_eq "6.3 下发后 source=user" "$(echo "$SYNC_VIEW" | jq -r '.source')" "user"
echo "$SYNC_VIEW" | jq -jr '.content' > "$WORKDIR/synced.md"
if cmp -s "$WORKDIR/synced.md" "$WORKDIR/skills/demo-a/SKILL.md"; then
  ok "6.4 下发内容与包内逐字节一致"
else
  bad "6.4 下发内容与包内不一致"
fi
svc_body GET "/skills/users/$UID_A/demo-a?file=scripts/hello.sh" | jq -jr '.content' > "$WORKDIR/synced.sh"
if cmp -s "$WORKDIR/synced.sh" "$WORKDIR/skills/demo-a/scripts/hello.sh"; then
  ok "6.5 资源文件亦下发且逐字节一致（scripts/hello.sh）"
else
  bad "6.5 资源文件未下发或内容不一致"
fi

###############################################################################
say "E7：负例（非法输入 / 不存在 / 超限）"
assert_svc_error "7.1 非法 userId → 400" GET "/skills/users/.hidden/demo-a" 400 invalid_user_id
assert_svc_error "7.2 非法技能名（路径穿越）→ 400" GET "/skills/users/$UID_A/a..b" 400 invalid_name
assert_svc_error "7.3 两侧都不存在 → 404" GET "/skills/users/$UID_A/ghost-skill" 404 not_found
assert_svc_error "7.4 删除无个人覆盖的技能 → 404" DELETE "/skills/users/us-ghost-$TS/demo-a" 404 not_found
assert_svc_error "7.5 包内无该技能 → 下发 404" POST "/skills/users/$UID_A/ghost-skill/sync-from-package" 404 not_found
python3 -c "
import json,sys
print(json.dumps({'content': 'x' * (100*1024 + 1)}))" > "$WORKDIR/big.json"
assert_svc_error "7.6 内容 >100KB → 413" PUT "/skills/users/$UID_A/demo-a" 413 content_too_large "$WORKDIR/big.json"

###############################################################################
if [ "${SANDBOX:-0}" == "1" ]; then
  say "E8：沙箱档 L4 回写（容器内 skill_manage → agent_fs，SANDBOX=1 专用）"
  # 背景：沙箱模式下工作区（含 skills/）落在容器内，call 结束由 WorkspaceSyncService 回写 KV。
  # 断言：会话里 skill_manage 新建的技能出现在 agents/{agent}/users/{uid}/skills␣ 命名空间。
  MYSQL_POD="${MYSQL_POD:-$(kubectl -n $NS get pod -l app=oaf-mysql -o jsonpath='{.items[0].metadata.name}')}"
  kv_query() { # kv_query <sql-file> —— 只读查 agent_fs（密码取 mysql pod 内环境变量，不落脚本）
    kubectl -n $NS exec -i "$MYSQL_POD" -- sh -c \
      'mysql -h127.0.0.1 -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "$(cat)"' < "$1" 2>/dev/null | grep -v '^mysql:'
  }

  UID_SBX="us-sbx-$TS"
  UID_SBX_OTHER="us-sbx-other-$TS"
  SBX_SKILL="sbx-l4-$TS"
  SBX_MARKER="SBX-L4-WRITEBACK-$TS"
  SBX_PROMPT="请用 skill_manage 工具（action=create）新建技能 $SBX_SKILL，SKILL.md 原样写入以下内容（含 frontmatter）：

---
name: $SBX_SKILL
description: sandbox L4 writeback probe
version: 1.0.0
---

# $SBX_SKILL

$SBX_MARKER

完成后只回复 done。"

  SBX_REPLY=""
  for attempt in 1 2; do
    [ $attempt -eq 2 ] && UID_SBX="us-sbx-$TS-r2"   # 重试换新用户，避免半途状态复放
    SBX_REPLY=$(a2a_send "$PUB_NAME" "$UID_SBX" "$SBX_PROMPT")
    if [[ "$SBX_REPLY" == *"done"* || "$SBX_REPLY" == *"完成"* || "$SBX_REPLY" == *"成功"* ]]; then
      ok "8.1 沙箱会话 skill_manage 受理 (uid=$UID_SBX)"
      break
    fi
    SBX_REPLY=""
  done
  [ -z "$SBX_REPLY" ] && bad "8.1 沙箱会话 skill_manage 未受理 reply=$(echo "$SBX_REPLY" | head -c 200)"

  # 回写在 call 结束（stop()）同步执行，A2A 返回即已落库；留 2s 容错
  sleep 2
  cat > "$WORKDIR/l4-rows.sql" <<SQL
select concat(replace(namespace_path,0x1F,'|'), item_key, '|v', version) from oaf_checkpoint.agent_fs
 where namespace_path like concat('agents',0x1F,'%','users',0x1F,'$UID_SBX',0x1F,'skills',0x1F)
   and item_key like '/$SBX_SKILL/%';
SQL
  L4_ROWS=$(kv_query "$WORKDIR/l4-rows.sql")
  echo "    rows: $(echo "$L4_ROWS" | head -3)"
  assert_contains "8.2 agent_fs 出现 L4 技能行（/{技能名}/SKILL.md）" "$L4_ROWS" "/$SBX_SKILL/SKILL.md"
  assert_contains "8.3 命名空间为 agents/{agent}/users/{uid}/skills" "$L4_ROWS" "|users|$UID_SBX|skills|"

  cat > "$WORKDIR/l4-marker.sql" <<SQL
select count(*) from oaf_checkpoint.agent_fs
 where namespace_path like concat('agents',0x1F,'%','users',0x1F,'$UID_SBX',0x1F,'skills',0x1F)
   and value_json like '%$SBX_MARKER%';
SQL
  assert_eq "8.4 KV 内容含会话内写入的 marker" "$(kv_query "$WORKDIR/l4-marker.sql")" "1"

  cat > "$WORKDIR/l4-isolation.sql" <<SQL
select count(*) from oaf_checkpoint.agent_fs
 where namespace_path like concat('agents',0x1F,'%','users',0x1F,'$UID_SBX_OTHER',0x1F,'skills',0x1F);
SQL
  assert_eq "8.5 其他用户命名空间无该技能（用户隔离）" "$(kv_query "$WORKDIR/l4-isolation.sql")" "0"

  cat > "$WORKDIR/l4-bare-ns.sql" <<SQL
select count(*) from oaf_checkpoint.agent_fs where namespace_path = concat('$UID_SBX',0x1F);
SQL
  assert_eq "8.6 回写不再落裸 userId 命名空间（命名空间对齐回归）" \
    "$(kv_query "$WORKDIR/l4-bare-ns.sql")" "0"

  #############################################################################
  say "E9：沙箱档回写仲裁（tombstone 防复活 / admin-override 防改回）"
  # 同代容器内 /workspace/skills 的副本会在每次 call 结束被回写，两个方向都必须有防护：
  #   DELETE 后若回写照旧 → 删除被静默还原（tombstone 防护）；
  #   PUT 后若回写照旧 → 管理面写入被容器内旧副本改回容器版本（admin-override 栅栏防护）。
  # E8 只证明「回写发生」，E9 证明两个仲裁都生效（同代容器 = 与 E8 同一 userId 连续 call）。
  # 边界：若两次 call 之间容器已换代（副本不存在），9.8/9.11 仍会通过但未覆盖“同代”路径。
  SBX_DEL_SKILL="sbx-del-$TS"
  SBX_DEL_MARKER="SBX-DEL-MARKER-$TS"
  SBX_ADMIN_MARKER="ADMIN-FENCE-$TS"

  sbx_l4_rows() { # 该技能在 agent_fs 的技能行（含 .deleted 标记）
    cat > "$WORKDIR/l4-sbx-rows.sql" <<SQL
select concat(item_key, '|v', version) from oaf_checkpoint.agent_fs
 where namespace_path like concat('agents',0x1F,'%','users',0x1F,'$UID_SBX',0x1F,'skills',0x1F)
   and item_key like '/$SBX_DEL_SKILL/%';
SQL
    kv_query "$WORKDIR/l4-sbx-rows.sql"
  }
  sbx_l4_content() { # 该技能 SKILL.md 的落库内容（无行则为空）
    cat > "$WORKDIR/l4-sbx-content.sql" <<SQL
select value_json from oaf_checkpoint.agent_fs
 where namespace_path like concat('agents',0x1F,'%','users',0x1F,'$UID_SBX',0x1F,'skills',0x1F)
   and item_key = '/$SBX_DEL_SKILL/SKILL.md';
SQL
    kv_query "$WORKDIR/l4-sbx-content.sql"
  }

  # 9.1 同代容器内建技能 → 回写落 KV（后续断言的基线）
  DEL_PROMPT="请用 skill_manage 工具（action=create）新建技能 $SBX_DEL_SKILL，SKILL.md 原样写入以下内容（含 frontmatter）：

---
name: $SBX_DEL_SKILL
description: sandbox tombstone probe
version: 1.0.0
---

# $SBX_DEL_SKILL

$SBX_DEL_MARKER

完成后只回复 done。"
  a2a_send "$PUB_NAME" "$UID_SBX" "$DEL_PROMPT" >/dev/null
  sleep 2
  assert_contains "9.1 同代容器内 skill_manage 建技能已回写落库（基线）" \
    "$(sbx_l4_rows)" "/$SBX_DEL_SKILL/SKILL.md"

  # 9.2~9.4 管理面 DELETE → 写 tombstone，且技能行被清空
  DEL_RESP_SBX=$(svc_body DELETE "/skills/users/$UID_SBX/$SBX_DEL_SKILL")
  DEL_FILES=$(echo "$DEL_RESP_SBX" | jq -r '.deletedFiles // 0')
  if [ "${DEL_FILES:-0}" -ge 1 ]; then
    ok "9.2 管理面 DELETE 命中 L4 覆盖 (deletedFiles=$DEL_FILES)"
  else
    bad "9.2 管理面 DELETE 未命中 L4 覆盖: $(echo "$DEL_RESP_SBX" | head -c 200)"
  fi
  assert_eq "9.3 删除响应暴露删除标记名（tombstone.name）" \
    "$(echo "$DEL_RESP_SBX" | jq -r '.tombstone.name')" "$SBX_DEL_SKILL"
  assert_contains "9.4 删除响应写明标记后果（同代容器内重建不会落库）" \
    "$(echo "$DEL_RESP_SBX" | jq -r '.message')" "不会被回写落库"
  assert_not_contains "9.5 删除后 KV 已无 SKILL.md 行" "$(sbx_l4_rows)" "/$SBX_DEL_SKILL/SKILL.md"
  assert_contains "9.6 删除后仅剩删除标记（tombstone）" "$(sbx_l4_rows)" "/$SBX_DEL_SKILL/.deleted"
  assert_svc_error "9.7 删除后明细回落（无包内同名技能 → 404）" \
    GET "/skills/users/$UID_SBX/$SBX_DEL_SKILL" 404 not_found

  # 9.8 再发一条同 uid 消息触发 stop() 回写：同代容器内副本不得把已删除技能“复活”
  a2a_send "$PUB_NAME" "$UID_SBX" '请只回复 ok。' >/dev/null
  sleep 2
  assert_not_contains "9.8 回写不得复活已删除技能（tombstone 生效）" \
    "$(sbx_l4_rows)" "/$SBX_DEL_SKILL/SKILL.md"

  # 9.9~9.12 管理面 PUT（写侧栅栏）→ 再发消息触发回写，KV 必须保持管理面内容
  PUT_SBX_MD="---
name: $SBX_DEL_SKILL
description: admin fence probe
version: 2.0.0
---

# $SBX_DEL_SKILL

$SBX_ADMIN_MARKER"
  python3 -c "
import json,sys
print(json.dumps({'content': sys.argv[1]}))" "$PUT_SBX_MD" > "$WORKDIR/put-sbx.json"
  PUT_RESP_SBX=$(svc_body PUT "/skills/users/$UID_SBX/$SBX_DEL_SKILL" "$(cat "$WORKDIR/put-sbx.json")")
  # 删除后 L4 已无该技能 → 本次 PUT 是重建（action=created）
  assert_eq "9.9 管理面 PUT 重建（清删除标记）" \
    "$(echo "$PUT_RESP_SBX" | jq -r '.action')" "created"
  assert_contains "9.10 写入响应说明写侧栅栏（不会被容器内旧副本改回）" \
    "$(echo "$PUT_RESP_SBX" | jq -r '.message')" "管理面写入栅栏"

  a2a_send "$PUB_NAME" "$UID_SBX" '请只回复 ok。' >/dev/null
  sleep 2
  assert_contains "9.11 回写不得改回容器内旧副本（admin-override 栅栏生效，KV 仍是管理面内容）" \
    "$(sbx_l4_content)" "$SBX_ADMIN_MARKER"
  assert_not_contains "9.12 KV 未残留容器内旧标记" "$(sbx_l4_content)" "$SBX_DEL_MARKER"
else
  say "E8/E9：沙箱档 L4 回写与回写仲裁（非沙箱档跳过）"
  echo "  SKIP 本用例仅 SANDBOX=1 生效；非沙箱路径由 E1-E7 的 KV 断言覆盖"
fi

# 清理（服务/包/临时目录）由 EXIT trap 负责：成功与失败路径都会回收
echo -e "\n\033[1;34m========== 结果 ==========\033[0m"
echo -e "PASS: $PASS  FAIL: $FAIL"
if [ $FAIL -gt 0 ]; then
  printf 'FAILED: %s\n' "${FAILED_CASES[@]}"
  exit 1
fi

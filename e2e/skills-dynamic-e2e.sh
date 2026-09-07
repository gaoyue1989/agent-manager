#!/usr/bin/env bash
# E2E 附：OAF skills 目录动态加载（oaf-skills-dynamic-loading-plan.md §8.2）
# 场景：
#   P  发布带 skills/demo-a 的包（agent-framework:latest）
#   1  基线：/skills + agent-card 含 demo-a；A2A 对话经 load_skill_through_path 可读
#   2  动态新增：宿主侧直接写 PVC packages/{id}/skills/demo-b → 不重启 → 即时可见可用
#   3  动态修改/删除：原位改 demo-a 描述 + 删 demo-b → 即时反映
#   4  沙箱档（SANDBOX=1）：技能脚本在沙箱内经 .skills-cache 投影可执行 + 动态新增脚本技能
#   5  L4 覆盖：skill_manage 修改包内同名技能 → 该用户生效、他用户不受影响（LLM 驱动）
# 前置：platform-backend running；新 agent-framework:latest 镜像已导入 kind 节点
#       （make docker-build && docker save | docker exec -i agent-manager-control-plane
#         ctr --namespace k8s.io images import -）
set -u
BASE="${BASE:-http://localhost:30080/api/v1}"
NS=agent-platform
SVC_NAME="e2e-skill-dyn"
SVC_SANDBOX_NAME="e2e-skill-dyn-sbx"
A2A_BASE="${A2A_BASE:-http://localhost:30080/agent}"
PASS=0; FAIL=0
declare -a FAILED_CASES

say()  { echo -e "\n\033[1;34m== $* ==\033[0m"; }
ok()   { PASS=$((PASS+1)); echo "  \033[32mPASS\033[0m $1"; }
bad()  { FAIL=$((FAIL+1)); FAILED_CASES+=("$1"); echo "  \033[31mFAIL\033[0m $1"; }
assert_eq() { if [ "$2" == "$3" ]; then ok "$1"; else bad "$1 (got: $2, want: $3)"; fi; }
assert_contains() { case "$2" in *"$3"*) ok "$1";; *) bad "$1 (missing: $3)";; esac; }
assert_not_contains() { case "$2" in *"$3"*) bad "$1 (should NOT contain: $3)";; *) ok "$1";; esac; }

source "$(dirname "$0")/../.env.secrets" 2>/dev/null || source /root/agent-manager/.env.secrets
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

# ---------- PVC 直写（kind 节点 local-path-provisioner 路径） ----------
PVC_UID=$(kubectl -n $NS get pvc platform-data -o jsonpath='{.spec.volumeName}')
NODE_PVC="/var/local-path-provisioner/${PVC_UID}_agent-platform_platform-data"
# 属主对齐 platform-backend 的 app 用户（10001）：直写文件必须可被平台删除，否则 DELETE packages 会 500
pvc_write() { # pvc_write <pkgId> <relpath> <content>  —— 文件级原位写入（保持 subPath 稳定）
  local rel="$1/$2"
  printf '%s' "$3" | docker exec -i agent-manager-control-plane sh -c \
    "mkdir -p '$NODE_PVC/packages/$(dirname "$rel")' && cat > '$NODE_PVC/packages/$rel' \
     && chmod -R 755 '$NODE_PVC/packages/$(dirname "$rel")' && chmod 644 '$NODE_PVC/packages/$rel' \
     && chown -R 10001:10001 '$NODE_PVC/packages/$1'"
}
pvc_rm() { docker exec agent-manager-control-plane rm -rf "$NODE_PVC/packages/$1"; }

# ---------- A2A 对话（唯一 userId 防暂停态复放） ----------
a2a_send() { # a2a_send <svc-name> <userId> <text> → 输出回复全文（超时 600s）
  local svc=$1 uid=$2 text=$3 payload resp
  payload=$(python3 -c "
import json,sys,time
print(json.dumps({'jsonrpc':'2.0','id':1,'method':'message/send','params':{
 'message':{'role':'user','parts':[{'kind':'text','text':sys.argv[1]}],'kind':'message',
 'messageId':'e2e-'+str(int(time.time())),'metadata':{'userId':sys.argv[2]}},
 }}))" "$text" "$uid")
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

# LLM 对话断言（宽匹配 marker；失败重试 1 次）
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

demo_skill_md() { # demo_skill_md <name> <description> <content-marker>
  printf -- '---\nname: %s\ndescription: %s\nversion: 1.0.0\n---\n\n# %s\n\nSkill content marker: %s\n' \
    "$1" "$2" "$1" "$3"
}

agents_md() {
  cat <<'EOF'
---
name: "Skill Dyn E2E"
vendorKey: "e2e"
agentKey: "skill-dyn"
version: "1.0.0"
slug: "e2e/skill-dyn"
description: "Skills dynamic loading E2E agent"
author: "@e2e"
license: "MIT"

skills:
  - name: "demo-a"
    source: "local"
    version: "1.0.0"
    required: true
---

# Skill Dyn E2E Agent

You are a test agent for OAF skills dynamic loading E2E.
When asked to read a skill, use the load_skill_through_path tool with the skill id and path SKILL.md,
then report the exact marker string found in the file content.
EOF
}

CKPT_URL="jdbc:mysql://oaf-mysql.$NS.svc.cluster.local:3306/oaf_checkpoint?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
runtime_env() { # runtime_env [sandbox:true|false]
  if [ "${1:-false}" == "true" ]; then
    # 沙箱 API key：默认取 release-agent 已验证配置（SDK ConnectionConfig 要求非空）
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
export CKPT_URL SANDBOX_SERVER_URL

cleanup_mine() { # 只清理本脚本创建的资源，不碰其他服务
  for n in "$SVC_NAME" "$SVC_SANDBOX_NAME"; do
    ID=$(api GET "/services?keyword=$n" | jq -r '.data[]? | select(.name=="'$n'") | .id' | head -1)
    [ -n "$ID" ] && [ "$ID" != "null" ] && api DELETE "/services/$ID" >/dev/null && echo "  cleaned old service $n (id=$ID)"
  done
}

SKILL_A_MD='---
name: demo-a
description: Demo skill A baseline
version: 1.0.0
---

# Demo A

Skill content marker: SKILLDEMOA-CONTENT-7f3a
'
DEMO_B_MD='---
name: demo-b
description: Demo skill B injected at runtime
version: 1.0.0
---

# Demo B

Skill content marker: MARKER-DEMO-B-9d2c
'
DEMO_A_UPDATED_MD='---
name: demo-a
description: Demo skill A updated in place
version: 1.1.0
---

# Demo A

Skill content marker: UPDATED-DEMO-A-4e8b
'
HELLO_SH='#!/bin/sh
echo "hello-from-demo-a"
'

###############################################################################
say "P：构造并发布带 skills 的配置包"
cleanup_mine
WORKDIR=$(mktemp -d)
AGENTS_MD_FILE="$WORKDIR/AGENTS.md"; agents_md > "$AGENTS_MD_FILE"
SKILLS_DIR="$WORKDIR/skills"
mkdir -p "$SKILLS_DIR/demo-a/scripts"
printf '%s' "$SKILL_A_MD" > "$SKILLS_DIR/demo-a/SKILL.md"
printf '%s' "$HELLO_SH" > "$SKILLS_DIR/demo-a/scripts/hello.sh"
chmod +x "$SKILLS_DIR/demo-a/scripts/hello.sh"
ZIPFILE="$WORKDIR/skill-dyn-agent.zip"
(cd "$WORKDIR" && zip -qr "$ZIPFILE" AGENTS.md skills)
UP=$(upload_zip "$ZIPFILE")
PKG_ID=$(echo "$UP" | jq -r '.data.id')
[ -n "$PKG_ID" ] && [ "$PKG_ID" != "null" ] && ok "P1 上传带 skills 的包 (packageId=$PKG_ID)" || { bad "P1 上传失败"; exit 1; }

PUB=$(api POST /services "{\"packageId\":$PKG_ID,\"image\":\"agent-framework:latest\",\"env\":$(runtime_env false)}")
SVC_ID=$(echo "$PUB" | jq -r '.data.id')
[ -n "$SVC_ID" ] && [ "$SVC_ID" != "null" ] && ok "P2 发布受理 (svcId=$SVC_ID)" || { bad "P2 发布失败"; exit 1; }
if wait_status "$SVC_ID" running 300; then ok "P3 服务 running"; else bad "P3 未达 running"; exit 1; fi

SVC_URL="$A2A_BASE/$SVC_NAME"
# /skills 就绪重试：running 状态可能先于 Spring MVC 完全就绪（首个请求返回空）
wait_endpoint_json() { # wait_endpoint_json <url> <timeout_sec>
  local url=$1 timeout=${2:-60} waited=0 body=""
  while true; do
    body=$(curl -s --max-time 10 "$url")
    [ -n "$body" ] && echo "$body" | python3 -c "import json,sys; json.load(sys.stdin)" 2>/dev/null && { echo "$body"; return 0; }
    [ $waited -ge $timeout ] && { echo ""; return 1; }
    sleep 3; waited=$((waited+3))
  done
}
SKILLS_JSON=$(wait_endpoint_json "$SVC_URL/skills")
POD=$(kubectl -n $NS get pod -l app.kubernetes.io/name="oaf-$SVC_NAME" -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
RESTARTS_BEFORE=$(kubectl -n $NS get pod "$POD" -o jsonpath='{.status.containerStatuses[0].restartCount}' 2>/dev/null)

say "E-SKILL-1：基线（启动时技能可见可用）"
assert_contains "1.1 /skills 含 demo-a" "$SKILLS_JSON" '"name":"demo-a"'
DEMO_A_ENTRY=$(echo "$SKILLS_JSON" | python3 -c "
import json,sys
for s in json.load(sys.stdin):
    if s.get('name')=='demo-a': print(json.dumps(s)); break")
assert_eq "1.2 demo-a source=local（frontmatter 声明）" \
  "$(echo "$DEMO_A_ENTRY" | jq -r '.source')" "local"
CARD_JSON=$(curl -s "$SVC_URL/.well-known/agent-card.json")
echo "$CARD_JSON" | python3 -c "
import json,sys
card=json.load(sys.stdin)
ids=[s.get('id') for s in card.get('skills',[])]
sys.exit(0 if 'demo-a' in ids else 1)" && ok "1.3 agent-card 含 demo-a" || bad "1.3 agent-card 未含 demo-a"
a2a_assert_marker "1.4 对话可读 demo-a 内容（load_skill_through_path）" "$SVC_NAME" "e2e-s1-$(date +%s)" \
  "请读取技能 demo-a 的 SKILL.md，报告文件中 Skill content marker 后面的标记字符串。" \
  "SKILLDEMOA-CONTENT-7f3a"

say "E-SKILL-2：动态新增（宿主直写 PVC，不重启）"
pvc_write "$PKG_ID" "skills/demo-b/SKILL.md" "$DEMO_B_MD" && ok "2.1 PVC 原位写入 skills/demo-b" || bad "2.1 PVC 写入失败"
sleep 3  # bind mount 文件级变化实时可见；少量 sleep 容忍 kubelet/内核缓存
SKILLS_JSON2=$(curl -s "$SVC_URL/skills")
DEMO_B_ENTRY=$(echo "$SKILLS_JSON2" | python3 -c "
import json,sys
for s in json.load(sys.stdin):
    if s.get('name')=='demo-b': print(json.dumps(s)); break")
[ -n "$DEMO_B_ENTRY" ] && ok "2.2 /skills 即时含 demo-b（不重启）" || bad "2.2 /skills 未含 demo-b"
assert_eq "2.3 demo-b source=local-dynamic（目录独有标记）" \
  "$(echo "$DEMO_B_ENTRY" | jq -r '.source')" "local-dynamic"
assert_eq "2.4 demo-b dynamic=true" "$(echo "$DEMO_B_ENTRY" | jq -r '.dynamic')" "true"
RESTARTS_AFTER=$(kubectl -n $NS get pod "$POD" -o jsonpath='{.status.containerStatuses[0].restartCount}' 2>/dev/null)
assert_eq "2.5 Pod 未重启" "$RESTARTS_AFTER" "$RESTARTS_BEFORE"
a2a_assert_marker "2.6 对话可读动态新增的 demo-b" "$SVC_NAME" "e2e-s2-$(date +%s)" \
  "请读取技能 demo-b 的 SKILL.md，报告文件中 Skill content marker 后面的标记字符串。" \
  "MARKER-DEMO-B-9d2c"

say "E-SKILL-3：动态修改/删除"
pvc_write "$PKG_ID" "skills/demo-a/SKILL.md" "$DEMO_A_UPDATED_MD"
sleep 2
SKILLS_JSON3=$(curl -s "$SVC_URL/skills")
DEMO_A_ENTRY2=$(echo "$SKILLS_JSON3" | python3 -c "
import json,sys
for s in json.load(sys.stdin):
    if s.get('name')=='demo-a': print(json.dumps(s)); break")
assert_eq "3.1 demo-a 描述原位更新" "$(echo "$DEMO_A_ENTRY2" | jq -r '.description')" "Demo skill A updated in place"
assert_eq "3.2 demo-a 版本随目录更新" "$(echo "$DEMO_A_ENTRY2" | jq -r '.version')" "1.1.0"
pvc_rm "$PKG_ID/skills/demo-b"
sleep 2
SKILLS_JSON4=$(curl -s "$SVC_URL/skills")
assert_not_contains "3.3 删除后 /skills 不再含 demo-b" "$SKILLS_JSON4" '"name":"demo-b"'
a2a_assert_marker "3.4 对话读到 demo-a 原位更新内容" "$SVC_NAME" "e2e-s3-$(date +%s)" \
  "请读取技能 demo-a 的 SKILL.md，报告文件中 Skill content marker 后面的标记字符串。" \
  "UPDATED-DEMO-A-4e8b"

if [ "${SANDBOX:-0}" == "1" ]; then
  say "E-SKILL-4：沙箱档（.skills-cache 物化 + 投影 + 脚本执行）"
  # 显式 name：k8sName/ingress 路径由 name 派生，避免与非沙箱服务（同 slug）派生名冲突
  PUB2=$(api POST /services "{\"packageId\":$PKG_ID,\"name\":\"$SVC_SANDBOX_NAME\",\"image\":\"agent-framework:latest\",\"env\":$(runtime_env true)}")
  SVC2_ID=$(echo "$PUB2" | jq -r '.data.id')
  if [ -n "$SVC2_ID" ] && [ "$SVC2_ID" != "null" ] && wait_status "$SVC2_ID" running 300; then
    ok "4.1 沙箱服务 running"
    a2a_assert_marker "4.2 沙箱内执行 demo-a 脚本（.skills-cache 投影）" "$SVC_SANDBOX_NAME" "e2e-s4-$(date +%s)" \
      "请执行技能 demo-a 中的脚本 scripts/hello.sh（可用 shell 工具），报告脚本的标准输出内容。" \
      "hello-from-demo-a"
    pvc_write "$PKG_ID" "skills/demo-c/SKILL.md" "$(demo_skill_md demo-c 'Runtime demo C with script' 'MARKER-DEMO-C-1c9f')"
    pvc_write "$PKG_ID" "skills/demo-c/scripts/whoami.sh" '#!/bin/sh
echo "runtime-demo-c-ok"'
    sleep 3
    a2a_assert_marker "4.3 动态新增的 demo-c 脚本在沙箱内可执行" "$SVC_SANDBOX_NAME" "e2e-s4b-$(date +%s)" \
      "请读取技能 demo-c 的 SKILL.md 报告其中 marker，然后执行技能 demo-c 中的脚本 scripts/whoami.sh，报告标准输出。" \
      "runtime-demo-c-ok"
  else
    bad "4.1 沙箱服务未达 running"
  fi
  [ -n "$SVC2_ID" ] && [ "$SVC2_ID" != "null" ] && api DELETE "/services/$SVC2_ID" >/dev/null && echo "  deleted service $SVC_SANDBOX_NAME"
fi

say "E-SKILL-5：L4 覆盖（skill_manage 用户覆盖包内技能，LLM 驱动）"
OVERRIDE_REPLY=""
for attempt in 1 2; do
  UID5="e2e-s5-$(date +%s)-a$attempt"  # 重试换新会话（防半途状态复放）
  OVERRIDE_REPLY=$(a2a_send "$SVC_NAME" "$UID5" \
    "请用 skill_manage 工具修改技能 demo-a：把文件最后一行追加为 'L4-OVERRIDE-5b7d'（保留原有内容）。完成后回复 done。")
  if [[ -n "$OVERRIDE_REPLY" && "$OVERRIDE_REPLY" != "<empty-response>" && "$OVERRIDE_REPLY" != "<non-json-response>" ]] \
     && [[ "$OVERRIDE_REPLY" == *"done"* || "$OVERRIDE_REPLY" == *"modified"* || "$OVERRIDE_REPLY" == *"成功"* || "$OVERRIDE_REPLY" == *"完成"* ]]; then
    ok "5.1 skill_manage 修改受理"
    break
  fi
  [ $attempt -eq 1 ] && { echo "    retry skill_manage..."; sleep 5; }
  OVERRIDE_REPLY=""
done
if [ -n "$OVERRIDE_REPLY" ]; then
  a2a_assert_marker "5.2 同用户可见 L4 覆盖" "$SVC_NAME" "$UID5" \
    "请读取技能 demo-a 的 SKILL.md，报告文件中是否包含 L4-OVERRIDE-5b7d。" \
    "L4-OVERRIDE-5b7d"
  OTHER_REPLY=$(a2a_send "$SVC_NAME" "e2e-s5-other-$(date +%s)" \
    "请读取技能 demo-a 的 SKILL.md，报告文件中是否包含 L4-OVERRIDE-5b7d（只回答包含或不包含）。")
  if [[ "$OTHER_REPLY" == *"不包含"* || "$OTHER_REPLY" == *"not contain"* || "$OTHER_REPLY" == *"没有"*"L4-OVERRIDE"* ]]; then
    ok "5.3 其他用户不受 L4 覆盖影响"
  else
    bad "5.3 其他用户会话疑似受影响 reply=$(echo "$OTHER_REPLY" | head -c 200)"
  fi
else
  bad "5.1 skill_manage 修改未受理 reply=$(echo "$OVERRIDE_REPLY" | head -c 200)"
fi

say "清理"
api DELETE "/services/$SVC_ID" >/dev/null && echo "  deleted service $SVC_NAME"
api DELETE "/packages/$PKG_ID" >/dev/null && echo "  deleted package $PKG_ID"
rm -rf "$WORKDIR"

echo -e "\n\033[1;34m========== 结果 ==========\033[0m"
echo -e "PASS: $PASS  FAIL: $FAIL"
if [ $FAIL -gt 0 ]; then
  printf 'FAILED: %s\n' "${FAILED_CASES[@]}"
  exit 1
fi

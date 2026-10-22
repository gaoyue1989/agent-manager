#!/usr/bin/env bash
# M10 · 沙箱配置（console 创建/编辑表单 → 包声明 + SANDBOX_* env → 只读总览三层裁决）
# 覆盖：enabled=true/false/未声明；资源规格注入与空/负值归一；env > package > default
#       三层来源；edit-form 回显；keyword/member/跨空间；SANDBOX_ENABLED 不经 env 注入。
# 说明：真实沙箱容器执行由 agent-framework e2e-sandbox job（mock OpenSandbox）覆盖，
#       本套件只验证平台控制面契约（CI 无 OpenSandbox Server）。
source "$(dirname "$0")/lib.sh"

TS=$(date +%s)
TOKEN=$(admin_login)
[ -n "$TOKEN" ] || { say "❌ admin 登录失败"; exit 1; }
say "-- M10 沙箱配置"

WS=$(seed_ws "$TOKEN" "m10-ws-$TS")
WS2=$(seed_ws "$TOKEN" "m10-ws2-$TS")
resolve_llm_env
if [ -z "$LLM_KEY" ] || [ -z "$LLM_BASE" ]; then
  skip "LLM 凭据不可得，M10 需 verified 连接创建 Agent"; finish "M10-sandbox"; exit 2
fi
MC=$(req POST /console/workspaces/$WS/model-connections \
  "{\"name\":\"m10-llm-$TS\",\"modelId\":\"$LLM_MODEL\",\"baseUrl\":\"$LLM_BASE\",\"apiKey\":\"$LLM_KEY\"}" "" "$TOKEN" | jqr "['data']['id']")
ensure_verified "$TOKEN" "$WS" "$MC" >/dev/null

A1=""; A2=""; A3=""; A4=""; A5=""
cleanup_m10() {
  for id in $A1 $A2 $A3 $A4 $A5; do
    [ -n "$id" ] && code DELETE /console/workspaces/$WS/agents/$id "" "$TOKEN" >/dev/null 2>&1
  done
}
trap cleanup_m10 EXIT

mkform() { # mkform <name> <sandbox-json>
  python3 -c "
import json,sys
d={'mode':'harness','name':sys.argv[1],'description':'M10 sandbox e2e',
 'modelConnections':[{'connectionId':$MC,'isDefault':True}],
 'prompt':'你是 M10 沙箱配置验证助手。','skills':[],'subAgents':[],'mcpServers':[],'replicas':1}
if sys.argv[2]:
    d['sandbox']=json.loads(sys.argv[2])
print(json.dumps(d))" "$1" "$2"
}
create_agent() { # create_agent <name> <sandbox-json> -> echo svcId
  local out
  out=$(req POST /console/workspaces/$WS/agents "$(mkform "$1" "$2")" "" "$TOKEN")
  echo "$out" | jqr "['data']['service']['id']"
}
pkg_of() { # pkg_of <svcId> -> package id
  req GET /console/workspaces/$WS/agents/$1 "" "$TOKEN" | jqr "['data']['packageId']"
}
env_json_of() { # env_json_of <svcId> -> env JSON string
  req GET /console/workspaces/$WS/agents/$1 "" "$TOKEN" | jqr "['data']['envJson']"
}
md_of_pkg() { # md_of_pkg <pkgId>
  curl -s "$BASE/packages/$1/download" -H "Authorization: Bearer $TOKEN" | python3 -c "
import zipfile,sys
print(zipfile.ZipFile(__import__('io').BytesIO(sys.stdin.buffer.read())).read('AGENTS.md').decode())"
}
overview_row() { # overview_row <keyword>
  req GET "/console/workspaces/$WS/sandbox?keyword=$1" "" "$TOKEN" | python3 -c "
import sys,json
raw=sys.stdin.read(); rows=json.loads(raw[raw.index('{'):])['data']
print(json.dumps(rows[0]) if rows else '{}')"
}

# ---- A：enabled=true + 全量资源规格 ----
A1=$(create_agent "m10-on-$TS" '{"enabled":true,"image":"opensandbox/code-interpreter:v1.1.0","timeoutMinutes":90,"memoryMb":2048,"cpuCount":2}')
assert_eq "A1 沙箱开启 Agent 创建" "$([ -n "$A1" ] && [ "$A1" != "None" ] && echo yes || echo no)" "yes"
A1ST=$(wait_status "$TOKEN" "$WS" "$A1" 240 running register_failed deploy_failed)
assert_eq "A1 Agent running" "$A1ST" "running"
A1PKG=$(pkg_of "$A1")
A1MD=$(md_of_pkg "$A1PKG")
assert_contains "A2 包声明 config.sandbox.enabled=true" "$A1MD" "enabled: true"
A1ENV=$(env_json_of "$A1")
A1CHK=$(python3 -c "
import json,sys
e=json.loads(sys.argv[1] or '{}')
print(json.dumps({
 'image':e.get('SANDBOX_IMAGE'),
 'timeout':e.get('SANDBOX_TIMEOUT_MINUTES'),
 'memory':e.get('SANDBOX_MEMORY_MB'),
 'cpu':e.get('SANDBOX_CPU_COUNT'),
 'enabled':e.get('SANDBOX_ENABLED'),
}, sort_keys=True))" "$A1ENV")
assert_eq "A3 资源规格 env 注入且 SANDBOX_ENABLED 不注入" "$A1CHK" \
  '{"cpu": "2", "enabled": null, "image": "opensandbox/code-interpreter:v1.1.0", "memory": "2048", "timeout": "90"}'
A1ROW=$(overview_row "m10-on-$TS")
assert_eq "A4 总览来源=package" "$(echo "$A1ROW" | jqr "['source']")" "package"
assert_eq "A5 总览 enabled=true" "$(echo "$A1ROW" | jqr "['enabled']")" "True"
assert_eq "A6 总览 packageDeclared=true" "$(echo "$A1ROW" | jqr "['packageDeclared']")" "True"
assert_eq "A7 总览资源回显（image/timeout/memory/cpu）" \
  "$(echo "$A1ROW" | jqr "['image'] + '|' + str(d['timeoutMinutes']) + '|' + str(d['memoryMb']) + '|' + str(d['cpuCount'])")" \
  "opensandbox/code-interpreter:v1.1.0|90|2048|2"
A1EF=$(req GET /console/workspaces/$WS/agents/$A1/edit-form "" "$TOKEN")
assert_contains "A8 edit-form 沙箱回显 enabled" "$A1EF" '"enabled":true'
assert_contains "A8 edit-form 沙箱镜像回显" "$A1EF" "opensandbox/code-interpreter:v1.1.0"

# ---- B：显式 false（copy-on-write 必须落包 false）----
A2=$(create_agent "m10-off-$TS" '{"enabled":false}')
B2ST=$(wait_status "$TOKEN" "$WS" "$A2" 240 running register_failed deploy_failed)
assert_eq "B1 显式关闭 Agent running" "$B2ST" "running"
A2PKG=$(pkg_of "$A2")
A2MD=$(md_of_pkg "$A2PKG")
assert_contains "B2 包声明 enabled=false" "$A2MD" "enabled: false"
A2ENV=$(env_json_of "$A2")
assert_eq "B3 关闭时无 SANDBOX_* env" \
  "$(python3 -c "
import json,sys
e=json.loads(sys.argv[1] or '{}')
print(sorted(k for k in e if k.startswith('SANDBOX_')))" "$A2ENV")" "[]"
A2ROW=$(overview_row "m10-off-$TS")
assert_eq "B4 总览来源=package" "$(echo "$A2ROW" | jqr "['source']")" "package"
assert_eq "B5 总览 enabled=false" "$(echo "$A2ROW" | jqr "['enabled']")" "False"
assert_eq "B6 总览 packageDeclared=false" "$(echo "$A2ROW" | jqr "['packageDeclared']")" "False"

# ---- C：未声明沙箱（三层均无 → default false）----
A3=$(create_agent "m10-none-$TS" "")
C3ST=$(wait_status "$TOKEN" "$WS" "$A3" 240 running register_failed deploy_failed)
assert_eq "C1 未声明 Agent running" "$C3ST" "running"
A3PKG=$(pkg_of "$A3")
A3MD=$(md_of_pkg "$A3PKG")
assert_not_contains "C2 包无 sandbox 声明" "$A3MD" "sandbox:"
A3ROW=$(overview_row "m10-none-$TS")
assert_eq "C3 总览来源=default" "$(echo "$A3ROW" | jqr "['source']")" "default"
assert_eq "C4 总览 enabled=false" "$(echo "$A3ROW" | jqr "['enabled']")" "False"
assert_eq "C5 未声明时 packageDeclared 缺省" "$(echo "$A3ROW" | jqr "['packageDeclared'] if 'packageDeclared' in d else 'absent'")" "absent"

# ---- D：enabled=true 但资源留空 → 不注入 SANDBOX_* ----
A4=$(create_agent "m10-emptyres-$TS" '{"enabled":true,"image":"","timeoutMinutes":0,"memoryMb":0,"cpuCount":0}')
D4ST=$(wait_status "$TOKEN" "$WS" "$A4" 240 running register_failed deploy_failed)
assert_eq "D1 空资源 Agent running" "$D4ST" "running"
A4MD=$(md_of_pkg "$(pkg_of "$A4")")
assert_contains "D2 包声明 enabled=true" "$A4MD" "enabled: true"
assert_eq "D3 空资源不注入 SANDBOX_* env" \
  "$(python3 -c "
import json,sys
e=json.loads(sys.argv[1] or '{}')
print(sorted(k for k in e if k.startswith('SANDBOX_')))" "$(env_json_of "$A4")")" "[]"

# ---- E：负值归一为 0（不注入）----
A5=$(create_agent "m10-neg-$TS" '{"enabled":true,"image":"","timeoutMinutes":-1,"memoryMb":-5,"cpuCount":-2}')
E5ST=$(wait_status "$TOKEN" "$WS" "$A5" 240 running register_failed deploy_failed)
assert_eq "E1 负值 Agent running" "$E5ST" "running"
assert_eq "E2 负值归一后不注入 SANDBOX_* env" \
  "$(python3 -c "
import json,sys
e=json.loads(sys.argv[1] or '{}')
print(sorted(k for k in e if k.startswith('SANDBOX_')))" "$(env_json_of "$A5")")" "[]"
assert_contains "E3 负值仍落包声明 true" "$(md_of_pkg "$(pkg_of "$A5")")" "enabled: true"

# ---- F：env 优先于包声明 ----
# F1 包声明 true + env SANDBOX_ENABLED=false → 生效 false，来源 env
A1ENV2=$(python3 -c "
import json,sys
e=json.loads(sys.argv[1] or '{}'); e['SANDBOX_ENABLED']='false'
print(json.dumps({'env':e}))" "$(env_json_of "$A1")")
assert_eq "F1 PATCH env 受理" \
  "$(legacy_req PATCH /services/$A1/env "$A1ENV2" | jqr "['data']['status']")" "deploying"
wait_status "$TOKEN" "$WS" "$A1" 240 running register_failed deploy_failed >/dev/null
A1ROW2=$(overview_row "m10-on-$TS")
assert_eq "F2 env 覆盖包声明（enabled=false）" "$(echo "$A1ROW2" | jqr "['enabled']")" "False"
assert_eq "F3 来源=env" "$(echo "$A1ROW2" | jqr "['source']")" "env"
assert_eq "F4 envExplicit=true" "$(echo "$A1ROW2" | jqr "['envExplicit']")" "True"
# F5 包声明 false + env SANDBOX_ENABLED=true → 生效 true
A2ENV2=$(python3 -c "
import json,sys
e=json.loads(sys.argv[1] or '{}'); e['SANDBOX_ENABLED']='true'
print(json.dumps({'env':e}))" "$(env_json_of "$A2")")
legacy_req PATCH /services/$A2/env "$A2ENV2" >/dev/null
wait_status "$TOKEN" "$WS" "$A2" 240 running register_failed deploy_failed >/dev/null
A2ROW2=$(overview_row "m10-off-$TS")
assert_eq "F5 env 覆盖包声明（enabled=true）" "$(echo "$A2ROW2" | jqr "['enabled']")" "True"
assert_eq "F6 来源=env" "$(echo "$A2ROW2" | jqr "['source']")" "env"

# ---- G：查询矩阵 ----
assert_eq "G1 keyword 无匹配返回空数组" \
  "$(req GET "/console/workspaces/$WS/sandbox?keyword=no-such-$TS" "" "$TOKEN" | jqr "['data']")" "[]"
assert_eq "G2 不存在空间总览 404" "$(code GET /console/workspaces/999999/sandbox "" "$TOKEN")" "404"
U1=$(seed_user "$TOKEN" "m10-member-$TS" "Pass12345!")
req POST /admin/workspaces/$WS/members "{\"userId\":$U1,\"role\":\"member\"}" "" "$TOKEN" >/dev/null
MT=$(req POST /auth/login "{\"username\":\"m10-member-$TS\",\"password\":\"Pass12345!\"}" | jqr "['data']['token']")
assert_eq "G3 member 读取总览 200" "$(code GET /console/workspaces/$WS/sandbox "" "$MT")" "200"

finish "M10-sandbox"

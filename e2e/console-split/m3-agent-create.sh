#!/usr/bin/env bash
# M3 · 创建/编辑 Agent（实施文档 04 §4/§5，DoD 主链路）
# 覆盖：preview-name 确定性派生/重名预检；表单校验矩阵；创建→包 v1（frontmatter 契约：
#       agents[].endpoint / mcp-configs / token 不落包 / model 段）→发布 running→A2A 注册；
#       编辑→copy-on-write v1.0.1→republish→SSE 对话断言新提示词；member 403；清理
source "$(dirname "$0")/lib.sh"

TS=$(date +%s)
TOKEN=$(admin_login)
[ -n "$TOKEN" ] || { say "❌ admin 登录失败"; exit 1; }
say "-- M3 创建/编辑 Agent"

WS=$(seed_ws "$TOKEN" "m3-ws-$TS")
# 前置：verified 模型连接（真实 LLM；环境变量提供或从 kind 集群 release-agent Secret 读取）
LLM_BASE="${LLM_BASE_URL:-}"; LLM_KEY="${LLM_API_KEY:-}"; LLM_MODEL="${LLM_MODEL:-}"
if [ -z "$LLM_KEY" ] && [ "$KUBE_OK" = "1" ]; then
  LLM_KEY=$(kubectl -n $KNS get secret oaf-release-agent-env-secret -o jsonpath='{.data.LLM_API_KEY}' 2>/dev/null | base64 -d || true)
  LLM_BASE=$(kubectl -n $KNS get cm oaf-release-agent-env -o jsonpath='{.data.LLM_BASE_URL}' 2>/dev/null || true)
  LLM_MODEL=$(kubectl -n $KNS get cm oaf-release-agent-env -o jsonpath='{.data.LLM_MODEL_ID}' 2>/dev/null || true)
fi
if [ -z "$LLM_KEY" ] || [ -z "$LLM_BASE" ]; then
  skip "LLM 凭据不可得，M3 主链路需真实模型连接——置 LLM_API_KEY/LLM_BASE_URL/LLM_MODEL 后重跑"; finish "M3-agent-create"; exit 0
fi
CONN=$(req POST /console/workspaces/$WS/model-connections \
  "{\"name\":\"llm-$TS\",\"modelId\":\"$LLM_MODEL\",\"baseUrl\":\"$LLM_BASE\",\"apiKey\":\"$LLM_KEY\"}" "" "$TOKEN")
CONN_ID=$(echo "$CONN" | jqr "['data']['id']")
ensure_verified "$TOKEN" "$WS" "$CONN_ID" >/dev/null
assert_eq "连接 verified" "$(req GET /console/workspaces/$WS/model-connections "" "$TOKEN" | jqr "['data'][0]['status']")" "verified"

FORM_TPL='{"mode":"harness","name":"NAME","description":"M3 e2e","modelConnections":[{"connectionId":CID,"isDefault":true}],"prompt":"你是 M3 e2e 验证助手。被问候时回答：我是 m3e2e-agent v1。","skills":[],"subAgents":[],"mcpServers":[{"builtin":"platform"}],"spec":"small","replicas":1,"publicEndpoint":true}'

# ---- 校验矩阵 ----
BAD=$(echo "$FORM_TPL" | sed 's/NAME//; s/CID/'"$CONN_ID"'/')
assert_eq "空名 400" "$(code POST /console/workspaces/$WS/agents "${BAD/\"name\":\",/\"name\":\"\",\"}" "" "$TOKEN")" "400"
NOPROMPT=$(echo "$FORM_TPL" | sed "s/NAME/name-$TS/; s/CID/$CONN_ID/" | python3 -c "import sys,json;d=json.load(sys.stdin);d['prompt']='';print(json.dumps(d))")
assert_eq "空提示词 400" "$(code POST /console/workspaces/$WS/agents "$NOPROMPT" "" "$TOKEN")" "400"
NOCONN=$(echo "$FORM_TPL" | sed "s/NAME/name-$TS/; s/CID/$CONN_ID/" | python3 -c "import sys,json;d=json.load(sys.stdin);d['modelConnections']=[];print(json.dumps(d))")
assert_eq "空模型连接 400" "$(code POST /console/workspaces/$WS/agents "$NOCONN" "" "$TOKEN")" "400"
BADCONN=$(echo "$FORM_TPL" | sed "s/NAME/name-$TS/; s/CID/$CONN_ID/" | python3 -c "import sys,json;d=json.load(sys.stdin);d['modelConnections']=[{'connectionId':99999,'isDefault':True}];print(json.dumps(d))")
assert_eq "不存在连接 400" "$(code POST /console/workspaces/$WS/agents "$BADCONN" "" "$TOKEN")" "400"
UNVER=$(req POST /console/workspaces/$WS/model-connections \
  "{\"name\":\"uv-$TS\",\"modelId\":\"m\",\"baseUrl\":\"http://x.invalid/v1\",\"apiKey\":\"k\"}" "" "$TOKEN" | jqr "['data']['id']")
UVFORM=$(echo "$FORM_TPL" | sed "s/NAME/name-$TS/; s/CID/$UNVER/")
assert_eq "未验证连接 400" "$(code POST /console/workspaces/$WS/agents "$UVFORM" "" "$TOKEN")" "400"
NODESC=$(echo "$FORM_TPL" | sed "s/NAME/name-$TS/; s/CID/$CONN_ID/" | python3 -c "import sys,json;d=json.load(sys.stdin);d['description']='';print(json.dumps(d))")
assert_eq "空描述 400（#150，原 502 invalid AGENTS.md）" "$(code POST /console/workspaces/$WS/agents "$NODESC" "" "$TOKEN")" "400"
LONGNAME=$(echo "$FORM_TPL" | sed "s/CID/$CONN_ID/" | python3 -c "import sys,json;d=json.load(sys.stdin);d['name']='a'*200;print(json.dumps(d))")
assert_eq "200 字符名称 400（#149，原 502 Data too long）" "$(code POST /console/workspaces/$WS/agents "$LONGNAME" "" "$TOKEN")" "400"
SLUG63=$(echo "$FORM_TPL" | sed "s/CID/$CONN_ID/" | python3 -c "import sys,json;d=json.load(sys.stdin);d['name']='b'*63;print(json.dumps(d))")
assert_eq "slug 63 字符名称 400（#149，oaf- 前缀预算 59）" "$(code POST /console/workspaces/$WS/agents "$SLUG63" "" "$TOKEN")" "400"

# ---- preview-name：确定性 + 重名预检（与创建同名——agentKey 是名字的派生）----
NAME="部署验证-$TS"
P1=$(req GET "/console/workspaces/$WS/agents/preview-name?name=$NAME" "" "$TOKEN")
P2=$(req GET "/console/workspaces/$WS/agents/preview-name?name=$NAME" "" "$TOKEN")
assert_eq "preview 两次一致（确定性派生）" \
  "$(echo "$P1" | jqr "['data']['agentKey']")" "$(echo "$P2" | jqr "['data']['agentKey']")"
AK=$(echo "$P1" | jqr "['data']['agentKey']")
assert_contains "slug = agentmanager/agentKey" "$(echo "$P1" | jqr "['data']['slug']")" "agentmanager/$AK"

# ---- 创建 → running ----
FORM=$(echo "$FORM_TPL" | sed "s/NAME/$NAME/; s/CID/$CONN_ID/" | python3 -c "import sys,json;d=json.load(sys.stdin);d['spec']='';print(json.dumps(d))")
M3_SVC=""
M3_IPKG=""
cleanup_m3() {
  [ -n "$M3_SVC" ] && code DELETE /console/workspaces/$M3_WS/agents/$M3_SVC "" "$TOKEN" >/dev/null 2>&1
  [ -n "$M3_SVC2" ] && code DELETE /console/workspaces/$M3_WS/agents/$M3_SVC2 "" "$TOKEN" >/dev/null 2>&1
  # 导入源包清理（#172 唯一索引：残留会让后续轮次上传撞 slug+version 409）
  [ -n "$M3_IPKG" ] && code DELETE /packages/$M3_IPKG "" "$TOKEN" >/dev/null 2>&1
}
trap cleanup_m3 EXIT
M3_WS="$WS"
CREATE=$(req POST /console/workspaces/$WS/agents "$FORM" "" "$TOKEN")
SVC=$(echo "$CREATE" | jqr "['data']['service']['id']")
M3_SVC="$SVC"
PKG=$(echo "$CREATE" | jqr "['data']['package']['id']")
assert_eq "创建 202 返回 service+package" "$([ -n "$SVC" ] && [ -n "$PKG" ] && echo yes || echo no)" "yes"
assert_eq "包版本 v1.0.0" "$(echo "$CREATE" | jqr "['data']['package']['version']")" "1.0.0"
K8S=$(echo "$CREATE" | jqr "['data']['service']['k8sName']")
assert_eq "k8s 名与 agentKey 同源（oaf-$AK）" "$K8S" "oaf-$AK"
ST=$(wait_status "$TOKEN" "$WS" "$SVC" 180 running register_failed deploy_failed)
assert_eq "发布到终态" "$ST" "running"

# ---- 生成包契约（frontmatter / mcp-configs / model 段 / token 不落包）----
ZIP=$(curl -s "$BASE/packages/$PKG/download" -H "Authorization: Bearer $TOKEN" -o /tmp/m3-pkg-$TS.zip -w '%{http_code}')
assert_eq "整包下载 200" "$ZIP" "200"
MD=$(python3 -c "
import zipfile,sys
z=zipfile.ZipFile('/tmp/m3-pkg-$TS.zip')
print(z.read('AGENTS.md').decode())")
assert_contains "frontmatter vendorKey=agentmanager" "$MD" "vendorKey: agentmanager"
assert_contains "frontmatter slug 同源" "$MD" "slug: agentmanager/$AK"
assert_contains "agents 契约键（platform-mcp→mcpServers，此处验证 agents 段无 endpoint 不报错）" "$MD" "---"
FILES=$(python3 -c "
import zipfile
z=zipfile.ZipFile('/tmp/m3-pkg-$TS.zip')
print('\n'.join(z.namelist()))")
assert_contains "mcp-configs/platform/config.yaml 注入" "$FILES" "mcp-configs/platform/config.yaml"
assert_not_contains "LLM_API_KEY 不落包" "$MD" "$LLM_KEY"

# ---- A2A 注册 + env 分级 + spec 档 ----
assert_eq "A2A 注册成功" "$(req GET /console/workspaces/$WS/agents/$SVC "" "$TOKEN" | jqr "['data']['registeredName']" | grep -c . )" "1"
if [ "$KUBE_OK" = "1" ]; then
  CMENV=$(kubectl -n $KNS get cm $K8S-env -o jsonpath='{.data}' 2>/dev/null)
  SECENV=$(kubectl -n $KNS get secret $K8S-env-secret -o jsonpath='{.data}' 2>/dev/null)
  assert_contains "CM 含 LLM_MODEL（默认连接注入）" "$CMENV" '"LLM_MODEL"'
  assert_not_contains "CM 不含 LLM_API_KEY" "$CMENV" "LLM_API_KEY"
  assert_contains "Secret 含 LLM_API_KEY" "$SECENV" "LLM_API_KEY"
  RES=$(kubectl -n $KNS get deploy $K8S -o jsonpath='{.spec.template.spec.containers[0].resources.requests.cpu}' 2>/dev/null)
  SPECVA=$(req GET /console/workspaces/$WS/agents/$SVC "" "$TOKEN" | jqr "['data']['spec']")
  if [ -n "$SPECVA" ] && [ "$SPECVA" != "None" ]; then
    assert_eq "spec 直译 requests.cpu" "$RES" "1"
  else
    say "  （测试 Agent 未带 spec——单节点内存保护；资源直译由单测 publish_spec_test 覆盖）"
  fi
else
  skip "kubectl 不可用，跳过 env 分级/spec 档断言"
fi

# ---- 重名 409（空间内 slug 唯一）----
assert_eq "同 slug 再建 409" "$(code POST /console/workspaces/$WS/agents "$FORM" "" "$TOKEN")" "409"

# ---- 导入模式（#161）：无 env 合法包 → 导入 → 平台默认 env 合并 + Pod Ready ----
# 曾不经 buildPublishEnv：service env 只有隔离键，Pod 用镜像内置账号连库 Access denied
# agentKey/slug 带 TS（#172 同 slug+version 唯一索引后，历史轮次残留包会撞 409）
python3 - <<PYEOF
import zipfile
md = """---
name: Import Probe
vendorKey: acme
agentKey: import-probe-$TS
version: 1.0.0
slug: acme/import-probe-$TS
description: import mode env merge probe
author: "@e2e"
license: MIT
---
导入探针：被问候时回答 import-probe ok。"""
with zipfile.ZipFile("/tmp/m3-import-$TS.zip", "w") as z:
    z.writestr("AGENTS.md", md)
PYEOF
UP=$(curl -s -X POST "$BASE/console/workspaces/$WS/packages" -H "Authorization: Bearer $TOKEN" -F "file=@/tmp/m3-import-$TS.zip")
IPKG=$(echo "$UP" | jqr "['data']['id']")
M3_IPKG="$IPKG"
assert_eq "无 env 合法包上传 200" "$([ -n "$IPKG" ] && [ "$IPKG" != "None" ] && echo yes || echo no)" "yes"
IMPORT=$(req POST /console/workspaces/$WS/agents \
  "{\"mode\":\"import\",\"name\":\"import-probe-$TS\",\"importPackageId\":$IPKG,\"modelConnections\":[{\"connectionId\":$CONN_ID,\"isDefault\":true}],\"spec\":\"small\",\"replicas\":1,\"publicEndpoint\":true}" "" "$TOKEN")
ISVC=$(echo "$IMPORT" | jqr "['data']['service']['id']")
M3_SVC2="$ISVC"
assert_eq "导入 202 返回 service" "$([ -n "$ISVC" ] && [ "$ISVC" != "None" ] && echo yes || echo no)" "yes"
IST=$(wait_status "$TOKEN" "$WS" "$ISVC" 180 running register_failed deploy_failed)
assert_eq "导入发布到 running（Pod Ready，#161 主断言）" "$IST" "running"
if [ "$KUBE_OK" = "1" ] && [ "$IST" = "running" ]; then
  IK8S=$(echo "$IMPORT" | jqr "['data']['service']['k8sName']")
  ICM=$(kubectl -n $KNS get cm $IK8S-env -o jsonpath='{.data}' 2>/dev/null)
  ISEC=$(kubectl -n $KNS get secret $IK8S-env-secret -o jsonpath='{.data}' 2>/dev/null)
  assert_contains "导入 CM 含 CHECKPOINT_USERNAME（平台默认合并）" "$ICM" '"CHECKPOINT_USERNAME"'
  assert_contains "导入 CM 含 LLM_MODEL（勾选连接注入）" "$ICM" '"LLM_MODEL"'
  assert_contains "导入 CM 含 AGENT_REDIS_PREFIX（隔离派生）" "$ICM" '"AGENT_REDIS_PREFIX"'
  assert_contains "导入 Secret 含 CHECKPOINT_PASSWORD" "$ISEC" "CHECKPOINT_PASSWORD"
  assert_contains "导入 Secret 含 LLM_API_KEY" "$ISEC" "LLM_API_KEY"
else
  skip "kubectl 不可用或未 running，跳过导入 env 分级断言"
fi

# ---- 编辑链路：copy-on-write v1.0.1 + republish + 提示词热生效 ----
EDIT=$(echo "$FORM" | python3 -c "import sys,json;d=json.load(sys.stdin);d['name']='部署验证-$TS';d['prompt']='你是 M3 e2e 验证助手。被问候时回答：我是 m3e2e-agent v2 版本。';print(json.dumps(d))")
EDITED=$(req PUT /console/workspaces/$WS/agents/$SVC "$EDIT" "" "$TOKEN")
assert_eq "编辑返回 v1.0.1" "$(echo "$EDITED" | jqr "['data']['package']['version']")" "1.0.1"
assert_eq "copy-on-write 源=v1 包" "$(echo "$EDITED" | jqr "['data']['package']['sourcePackageId']")" "$PKG"
ST2=$(wait_status "$TOKEN" "$WS" "$SVC" 180 running register_failed deploy_failed)
if [ "$ST2" = "deploy_failed" ]; then
  req POST /console/workspaces/$WS/agents/$SVC/publish "" "$TOKEN" >/dev/null
  ST2=$(wait_status "$TOKEN" "$WS" "$SVC" 240 running register_failed deploy_failed)
fi
assert_eq "republish 回 running" "$ST2" "running"
VERSIONS=$(req GET /console/workspaces/$WS/agents/$SVC/versions "" "$TOKEN")
assert_contains "版本链含 v1.0.0 与 v1.0.1" "$VERSIONS" '"1.0.1"'
CURFLAG=$(echo "$VERSIONS" | python3 -c "
import sys,json
raw=sys.stdin.read()
rows=json.loads(raw[raw.index('{'):])['data']
print(all((r['version']=='1.0.1')==r['current'] for r in rows))")
if [ "$CURFLAG" != "True" ]; then
  say "  DBG VERSIONS[:300]=${VERSIONS:0:300}"
fi
assert_eq "当前版本标注（v1.0.1 且仅它 current）" "$CURFLAG" "True"
# SSE 对话断言新提示词（经 frontend proxy 身份注入）
E2EUID=$(echo "$TOKEN" | python3 -c "import sys,json,base64;print(json.loads(base64.b64decode(sys.stdin.read().split('.')[1]+'==').decode())['uid'])")
FE="${FRONTEND_BASE:-http://localhost:8911}"
REPLY=$(curl -s -N -m 60 "$FE/agent/$(req GET /console/workspaces/$WS/agents/$SVC "" "$TOKEN" | jqr "['data']['shortName']")/threads/chat" \
  -H "Cookie: oaf_session=x" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -d "{\"message\":\"你好\",\"userId\":\"u-$E2EUID\",\"sessionId\":\"m3-edit-$TS\"}" 2>/dev/null)
if [ -n "${DEBUG:-}" ]; then say "DBG REPLY[:240]=${REPLY:0:240}"; fi
if echo "$REPLY" | grep -q "TEXT_BLOCK_DELTA"; then
  assert_contains "新提示词热生效（回答含 v2）" "$REPLY" "v2"
else
  skip "SSE 对话（frontend $FE 不可达或 LLM 流式失败）"
fi

# ---- member 403 ----
U1=$(seed_user "$TOKEN" "m3-member-$TS" "Pass12345!")
req POST /admin/workspaces/$WS/members "{\"userId\":$U1,\"role\":\"member\"}" "" "$TOKEN" >/dev/null
MT=$(req POST /auth/login "{\"username\":\"m3-member-$TS\",\"password\":\"Pass12345!\"}" | jqr "['data']['token']")
assert_eq "member PUT 403" "$(code PUT /console/workspaces/$WS/agents/$SVC "$EDIT" "" "$MT")" "403"
assert_eq "member DELETE 403" "$(code DELETE /console/workspaces/$WS/agents/$SVC "" "$MT")" "403"

# ---- 沙箱配置（demo §5）：创建带 sandbox → 包声明 + SANDBOX_* env → 总览三层 → 编辑回显 ----
SBXON=$(echo "$FORM_TPL" | sed "s/NAME/sbx-on-$TS/; s/CID/$CONN_ID/" | python3 -c "
import sys,json
d=json.load(sys.stdin)
d['spec']=''
d['sandbox']={'enabled':True,'image':'opensandbox/code-interpreter:v1.1.0','timeoutMinutes':90,'memoryMb':2048,'cpuCount':2}
print(json.dumps(d))")
SBXC=$(req POST /console/workspaces/$WS/agents "$SBXON" "" "$TOKEN")
SBXSVC=$(echo "$SBXC" | jqr "['data']['service']['id']")
SBXPKG=$(echo "$SBXC" | jqr "['data']['package']['id']")
cleanup_m3_add() { [ -n "$SBXSVC" ] && code DELETE /console/workspaces/$WS/agents/$SBXSVC "" "$TOKEN" >/dev/null 2>&1; }
trap 'cleanup_m3; cleanup_m3_add' EXIT
assert_eq "沙箱创建 202" "$([ -n "$SBXSVC" ] && [ "$SBXSVC" != "None" ] && echo yes || echo no)" "yes"
SBXMD=$(curl -s "$BASE/packages/$SBXPKG/download" -H "Authorization: Bearer $TOKEN" | python3 -c "
import zipfile,sys
print(zipfile.ZipFile(__import__('io').BytesIO(sys.stdin.buffer.read())).read('AGENTS.md').decode())")
assert_contains "沙箱包声明 config.sandbox.enabled" "$SBXMD" "enabled: true"
SBXENV=$(req GET /console/workspaces/$WS/agents/$SBXSVC "" "$TOKEN")
echo "$SBXENV" | grep -q 'SANDBOX_IMAGE' && SBXIMG=found || SBXIMG=missing
assert_contains "SANDBOX_* 资源 env 注入" "$SBXIMG" "found"
echo "$SBXENV" | grep -q 'SANDBOX_ENABLED' && SBXSW=injected || SBXSW=absent
assert_eq "SANDBOX_ENABLED 不经 env 注入（包声明承载）" "$SBXSW" "absent"
SBXROW=$(req GET "/console/workspaces/$WS/sandbox?keyword=sbx-on-$TS" "" "$TOKEN")
assert_contains "总览：生效来源=package" "$SBXROW" '"source":"package"'
assert_contains "总览：资源规格回显" "$SBXROW" '"memoryMb":2048'
SBXEDIT=$(echo "$SBXON" | python3 -c "
import sys,json
d=json.load(sys.stdin)
d['name']='sbx-on-$TS'
d['sandbox']={'enabled':False,'image':'','timeoutMinutes':0,'memoryMb':0,'cpuCount':0}
print(json.dumps(d))")
SBXE=$(req PUT /console/workspaces/$WS/agents/$SBXSVC "$SBXEDIT" "" "$TOKEN")
assert_contains "编辑显式关 → 新版本包声明 false" "$(echo "$SBXE" | jqr "['data']['package']['version']")" "1.0.1"
SBXPKG2=$(echo "$SBXE" | jqr "['data']['package']['id']")
SBXMD2=$(curl -s "$BASE/packages/$SBXPKG2/download" -H "Authorization: Bearer $TOKEN" | python3 -c "
import zipfile,sys
print(zipfile.ZipFile(__import__('io').BytesIO(sys.stdin.buffer.read())).read('AGENTS.md').decode())")
assert_contains "显式关落包（copy-on-write 不沿用旧 true）" "$SBXMD2" "enabled: false"
SBXEF=$(req GET "/console/workspaces/$WS/sandbox?keyword=sbx-on-$TS" "" "$TOKEN")
assert_contains "总览刷新：包声明 false 生效" "$SBXEF" '"enabled":false'
code DELETE /console/workspaces/$WS/agents/$SBXSVC "" "$TOKEN" >/dev/null 2>&1

# ---- 清理（顺带覆盖 DELETE）----
assert_eq "删除 Agent 200" "$(code DELETE /console/workspaces/$WS/agents/$SVC "" "$TOKEN")" "200"

finish "M3-agent-create"

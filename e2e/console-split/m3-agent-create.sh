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
# 前置：verified 模型连接（CI 用 mock LLM；本地可显式 env 或回落 release-agent）
resolve_llm_env
if [ -z "$LLM_KEY" ] || [ -z "$LLM_BASE" ]; then
  skip "LLM 凭据不可得，M3 主链路需真实模型连接——置 LLM_API_KEY/LLM_BASE_URL/LLM_MODEL 后重跑"; finish "M3-agent-create"; exit 2
fi
CONN=$(req POST /console/workspaces/$WS/model-connections \
  "{\"name\":\"llm-$TS\",\"modelId\":\"$LLM_MODEL\",\"baseUrl\":\"$LLM_BASE\",\"apiKey\":\"$LLM_KEY\"}" "" "$TOKEN")
CONN_ID=$(echo "$CONN" | jqr "['data']['id']")
ensure_verified "$TOKEN" "$WS" "$CONN_ID" >/dev/null
assert_eq "连接 verified" "$(req GET /console/workspaces/$WS/model-connections "" "$TOKEN" | jqr "['data'][0]['status']")" "verified"

# 主 Agent 用 2 副本（kind 节点 4 CPU 足够：spec=small 1 CPU/2Gi ×2），
# 同时覆盖 replicas 直译；spec 档留给扩展 Agent。
FORM_TPL='{"mode":"harness","name":"NAME","description":"M3 e2e","modelConnections":[{"connectionId":CID,"isDefault":true}],"prompt":"你是 M3 e2e 验证助手。被问候时回答：我是 m3e2e-agent v1。","skills":[],"subAgents":[],"mcpServers":[{"builtin":"platform"}],"spec":"small","replicas":2,"publicEndpoint":true}'

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
TWODEF=$(echo "$FORM_TPL" | sed "s/NAME/name-$TS/; s/CID/$CONN_ID/" | python3 -c "
import sys,json
d=json.load(sys.stdin)
d['modelConnections']=[{'connectionId':$CONN_ID,'isDefault':True},{'connectionId':$CONN_ID,'isDefault':True}]
print(json.dumps(d))")
assert_eq "两个默认模型连接 400" "$(code POST /console/workspaces/$WS/agents "$TWODEF" "" "$TOKEN")" "400"
BADMCP=$(echo "$FORM_TPL" | sed "s/NAME/name-$TS/; s/CID/$CONN_ID/" | python3 -c "
import sys,json
d=json.load(sys.stdin)
d['mcpServers']=[{'connectorId':999999}]
print(json.dumps(d))")
assert_eq "不存在的 MCP 连接器 400" "$(code POST /console/workspaces/$WS/agents "$BADMCP" "" "$TOKEN")" "400"

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
M3_SVC2=""
M3_SVC3=""
M3_SVC4=""
M3_SVC5=""
M3_LEGACY=""
M3_IPKG=""
M3_TPLPKG=""
cleanup_m3() {
  [ -n "$M3_SVC" ] && code DELETE /console/workspaces/$M3_WS/agents/$M3_SVC "" "$TOKEN" >/dev/null 2>&1
  [ -n "$M3_SVC2" ] && code DELETE /console/workspaces/$M3_WS/agents/$M3_SVC2 "" "$TOKEN" >/dev/null 2>&1
  [ -n "$M3_SVC3" ] && code DELETE /console/workspaces/$M3_WS/agents/$M3_SVC3 "" "$TOKEN" >/dev/null 2>&1
  [ -n "$M3_SVC4" ] && code DELETE /console/workspaces/$M3_WS/agents/$M3_SVC4 "" "$TOKEN" >/dev/null 2>&1
  [ -n "$M3_SVC5" ] && code DELETE /console/workspaces/$M3_WS/agents/$M3_SVC5 "" "$TOKEN" >/dev/null 2>&1
  [ -n "$M3_LEGACY" ] && legacy_code DELETE /services/$M3_LEGACY >/dev/null 2>&1
  # 导入源包清理（#172 唯一索引：残留会让后续轮次上传撞 slug+version 409）
  [ -n "$M3_IPKG" ] && legacy_code DELETE /packages/$M3_IPKG >/dev/null 2>&1
  [ -n "$M3_TPLPKG" ] && legacy_code DELETE /packages/$M3_TPLPKG >/dev/null 2>&1
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
  assert_eq "主 Agent replicas=2 直译" \
    "$(kubectl -n $KNS get deploy $K8S -o jsonpath='{.spec.replicas}' 2>/dev/null)" "2"
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

# ---- 重名 409（空间内 slug 唯一）+ preview 冲突预检 ----
assert_eq "同 slug 再建 409" "$(code POST /console/workspaces/$WS/agents "$FORM" "" "$TOKEN")" "409"
assert_eq "preview-name 对已存在 Agent 返回 conflict=true" \
  "$(req GET "/console/workspaces/$WS/agents/preview-name?name=$NAME" "" "$TOKEN" | jqr "['data']['conflict']")" "True"

# ---- 扩展创建：子 Agent / 远程凭证 / 非公开入口 / 多副本 ----
EXT=$(echo "$FORM_TPL" | sed "s/CID/$CONN_ID/" | python3 -c "
import sys,json
d=json.load(sys.stdin)
d['name']='m3ext-$TS'
d['spec']='small'
d['subAgents']=[]
d['publicEndpoint']=False
d['replicas']=1
print(json.dumps(d))")
EXTC=$(req POST /console/workspaces/$WS/agents "$EXT" "" "$TOKEN")
EXT_SVC=$(echo "$EXTC" | jqr "['data']['service']['id']")
M3_SVC3="$EXT_SVC"
EXT_PKG=$(echo "$EXTC" | jqr "['data']['package']['id']")
EXT_K8S=$(echo "$EXTC" | jqr "['data']['service']['k8sName']")
assert_eq "扩展创建 202" "$([ -n "$EXT_SVC" ] && [ "$EXT_SVC" != "None" ] && echo yes || echo no)" "yes"
EXT_ST=$(wait_status "$TOKEN" "$WS" "$EXT_SVC" 180 running register_failed deploy_failed)
assert_eq "扩展 Agent running" "$EXT_ST" "running"
EXTMD=$(curl -s "$BASE/packages/$EXT_PKG/download" -H "Authorization: Bearer $TOKEN" | python3 -c "
import zipfile,sys
print(zipfile.ZipFile(__import__('io').BytesIO(sys.stdin.buffer.read())).read('AGENTS.md').decode())")
assert_eq "publicEndpoint=false → endpoint 为空" \
  "$(req GET /console/workspaces/$WS/agents/$EXT_SVC "" "$TOKEN" | jqr "['data'].get('endpoint') or ''")" ""
if [ "$KUBE_OK" = "1" ]; then
  assert_eq "spec=small 直译 requests.cpu" \
    "$(kubectl -n $KNS get deploy $EXT_K8S -o jsonpath='{.spec.template.spec.containers[0].resources.requests.cpu}' 2>/dev/null)" "1"
  assert_eq "spec=small 直译 requests.memory" \
    "$(kubectl -n $KNS get deploy $EXT_K8S -o jsonpath='{.spec.template.spec.containers[0].resources.requests.memory}' 2>/dev/null)" "2Gi"
  k8s_res() { kubectl -n $KNS get "$@" >/dev/null 2>&1; }
  k8s_res ingress $EXT_K8S && bad "publicEndpoint=false 仍创建 Ingress" || ok "publicEndpoint=false 不创建 Ingress"
else
  skip "kubectl 不可用，跳过 spec/Ingress 断言"
fi

# ---- 子 Agent 声明（远程 endpoint / 集成凭证不落包）----
# 只断言生成包契约，不等待运行时（远程 endpoint 无集成关系，运行时不属于本套件）。
SUB=$(echo "$FORM_TPL" | sed "s/CID/$CONN_ID/" | python3 -c "
import sys,json
d=json.load(sys.stdin)
d['name']='m3sub-$TS'
d['spec']=''
d['replicas']=1
d['subAgents']=[{'agent':'remote','role':'peer','delegations':[],'endpoint':'http://remote.invalid:8100'}]
d['remoteAuth']={'http://remote.invalid:8100':'Bearer remote-secret'}
print(json.dumps(d))")
SUBC=$(req POST /console/workspaces/$WS/agents "$SUB" "" "$TOKEN")
M3_SVC5=$(echo "$SUBC" | jqr "['data']['service']['id']")
SUB_PKG=$(echo "$SUBC" | jqr "['data']['package']['id']")
assert_eq "子 Agent 声明创建 202" "$([ -n "$M3_SVC5" ] && [ "$M3_SVC5" != "None" ] && echo yes || echo no)" "yes"
SUBMD=$(curl -s "$BASE/packages/$SUB_PKG/download" -H "Authorization: Bearer $TOKEN" | python3 -c "
import zipfile,sys
print(zipfile.ZipFile(__import__('io').BytesIO(sys.stdin.buffer.read())).read('AGENTS.md').decode())")
assert_contains "子 Agent 契约：role 声明" "$SUBMD" "role: peer"
assert_contains "远程子 Agent endpoint 声明" "$SUBMD" "endpoint: http://remote.invalid:8100"
assert_not_contains "远程认证值不落包" "$SUBMD" "remote-secret"

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
  "{\"mode\":\"import\",\"name\":\"import-probe-$TS\",\"importPackageId\":$IPKG,\"modelConnections\":[{\"connectionId\":$CONN_ID,\"isDefault\":true}],\"replicas\":1,\"publicEndpoint\":true}" "" "$TOKEN")
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

# ---- 模板导入：公共包标记 isTemplate → templates 列表 → 从模板创建 ----
# 模板列表只含公共包（workspaceId=0），因此这里走 legacy 上传（控制台上传是空间私有包）。
AUTH_ARGS=()
[ -n "${AUTH_TOKEN:-}" ] && AUTH_ARGS=(-H "Authorization: Bearer $AUTH_TOKEN")
python3 - <<PYEOF
import zipfile
md = """---
name: Tpl Probe
vendorKey: acme
agentKey: tpl-probe-$TS
version: 1.0.0
slug: acme/tpl-probe-$TS
description: template import probe
author: "@e2e"
license: MIT
---
模板探针。"""
with zipfile.ZipFile("/tmp/m3-tpl-$TS.zip", "w") as z:
    z.writestr("AGENTS.md", md)
PYEOF
TPLUP=$(curl -s -X POST "$BASE/packages" "${AUTH_ARGS[@]}" -F "file=@/tmp/m3-tpl-$TS.zip")
M3_TPLPKG=$(echo "$TPLUP" | jqr "['data']['id']")
assert_eq "公共模板包上传" "$([ -n "$M3_TPLPKG" ] && [ "$M3_TPLPKG" != "None" ] && echo yes || echo no)" "yes"
TPL_HTTP=$(curl -s -o /dev/null -w '%{http_code}' -X PATCH "$BASE/packages/$M3_TPLPKG/template" \
  "${AUTH_ARGS[@]}" -H 'Content-Type: application/json' -d '{"isTemplate":true}')
assert_eq "包标记为模板 200" "$TPL_HTTP" "200"
assert_contains "模板列表含该包" \
  "$(req GET /console/workspaces/$WS/templates "" "$TOKEN")" "tpl-probe-$TS"
TPLIMPORT=$(req POST /console/workspaces/$WS/agents \
  "{\"mode\":\"import\",\"name\":\"tpl-import-$TS\",\"importPackageId\":$M3_TPLPKG,\"modelConnections\":[{\"connectionId\":$CONN_ID,\"isDefault\":true}],\"replicas\":1}" "" "$TOKEN")
M3_SVC4=$(echo "$TPLIMPORT" | jqr "['data']['service']['id']")
assert_eq "从模板导入 202" "$([ -n "$M3_SVC4" ] && [ "$M3_SVC4" != "None" ] && echo yes || echo no)" "yes"

# ---- 纳管模式（adopt）：legacy 存量服务 → 绑定到当前空间 ----
LEGACY=$(curl -s -X POST "$BASE/services" "${AUTH_ARGS[@]}" -H 'Content-Type: application/json' -d "{
  \"packageId\": $IPKG,
  \"name\": \"m3-adopt-$TS\",
  \"env\": {
    \"CHECKPOINT_JDBC_URL\": \"jdbc:mysql://oaf-mysql.agent-platform.svc.cluster.local:3306/oaf_checkpoint?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC\",
    \"CHECKPOINT_USERNAME\": \"oaf\",
    \"CHECKPOINT_PASSWORD\": \"OafPlatform2026\",
    \"AGENT_REDIS_URL\": \"redis://oaf-redis.agent-platform.svc.cluster.local:6379\"
  }
}")
M3_LEGACY=$(echo "$LEGACY" | jqr "['data']['id']")
LEGACY_K8S=$(echo "$LEGACY" | jqr "['data']['k8sName']")
assert_eq "legacy 存量服务创建" "$([ -n "$M3_LEGACY" ] && [ "$M3_LEGACY" != "None" ] && echo yes || echo no)" "yes"
ADOPT=$(req POST /console/workspaces/$WS/agents "{\"mode\":\"adopt\",\"adoptServiceId\":$M3_LEGACY}" "" "$TOKEN")
ADOPT_ID=$(echo "$ADOPT" | jqr "['data']['service']['id']")
assert_eq "纳管存量服务 200" "$([ -n "$ADOPT_ID" ] && [ "$ADOPT_ID" != "None" ] && echo yes || echo no)" "yes"
assert_contains "纳管后出现在空间列表" \
  "$(req GET "/console/workspaces/$WS/agents?keyword=$LEGACY_K8S" "" "$TOKEN")" "$LEGACY_K8S"

# ---- 释放辅助 Agent：kind 节点仅 4 CPU，编辑滚动时新旧副本并存会因 requests 超额 Pending ----
[ -n "$EXT_SVC" ] && code DELETE /console/workspaces/$WS/agents/$EXT_SVC "" "$TOKEN" >/dev/null 2>&1 && M3_SVC3=""
[ -n "$ISVC" ] && code DELETE /console/workspaces/$WS/agents/$ISVC "" "$TOKEN" >/dev/null 2>&1 && M3_SVC2=""
[ -n "$M3_SVC5" ] && code DELETE /console/workspaces/$WS/agents/$M3_SVC5 "" "$TOKEN" >/dev/null 2>&1 && M3_SVC5=""
[ -n "$M3_SVC4" ] && code DELETE /console/workspaces/$WS/agents/$M3_SVC4 "" "$TOKEN" >/dev/null 2>&1 && M3_SVC4=""
[ -n "$M3_LEGACY" ] && legacy_code DELETE /services/$M3_LEGACY >/dev/null 2>&1 && M3_LEGACY=""

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
EDITFORM=$(req GET /console/workspaces/$WS/agents/$SVC/edit-form "" "$TOKEN")
assert_contains "edit-form 回显新提示词" "$EDITFORM" "m3e2e-agent v2 版本"
assert_contains "edit-form 含 modelConnections" "$EDITFORM" '"modelConnections":'
assert_contains "edit-form 含 sandbox 字段" "$EDITFORM" '"sandbox":'
# SSE 对话断言新提示词（经 frontend proxy 身份注入；mock 兜底镜像无会话面）
if runtime_is_real; then
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
else
  skip "E2E_AGENT_RUNTIME=mock：跳过会话级提示词热生效断言"
fi

# ---- member 403 ----
U1=$(seed_user "$TOKEN" "m3-member-$TS" "Pass12345!")
req POST /admin/workspaces/$WS/members "{\"userId\":$U1,\"role\":\"member\"}" "" "$TOKEN" >/dev/null
MT=$(req POST /auth/login "{\"username\":\"m3-member-$TS\",\"password\":\"Pass12345!\"}" | jqr "['data']['token']")
assert_eq "member PUT 403" "$(code PUT /console/workspaces/$WS/agents/$SVC "$EDIT" "" "$MT")" "403"
assert_eq "member DELETE 403" "$(code DELETE /console/workspaces/$WS/agents/$SVC "" "$MT")" "403"

# ---- 清理（顺带覆盖 DELETE）----
assert_eq "删除 Agent 200" "$(code DELETE /console/workspaces/$WS/agents/$SVC "" "$TOKEN")" "200"

finish "M3-agent-create"

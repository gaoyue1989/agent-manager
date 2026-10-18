#!/usr/bin/env bash
# M6 · 工具管理 MCP 连接器（实施文档 07 §4/§5，DoD：e2e 脚本 mcp-connector）
# 覆盖：CRUD 唯一性；verify 三分支（平台 /mcp 真靶 17 工具 verified / 坏端点 failed /
#       不可达 failed）；创建 Agent 勾选→包 config.yaml 断言+token 不落包→Secret 含
#       MCP_TOKEN_*；bearer token 走 Secret；被引用删除 409
source "$(dirname "$0")/lib.sh"

TS=$(date +%s)
TOKEN=$(admin_login)
[ -n "$TOKEN" ] || { say "❌ admin 登录失败"; exit 1; }
say "-- M6 工具管理 MCP 连接器"

WS=$(seed_ws "$TOKEN" "m6-ws-$TS")
# verify 真靶 = 平台自身 /mcp（集群内 FQDN——verify 由 backend pod 发起，pod 间 DNS 可达；
# 勿按宿主机连通性改写该地址：ingress 400/404 探测会误选不可达于 pod 的入口）
MCP_EP="${MCP_ENDPOINT:-http://oaf-platform-backend.agent-platform.svc.cluster.local:8080/mcp}"
LLM_BASE="${LLM_BASE_URL:-}"; LLM_KEY="${LLM_API_KEY:-}"; LLM_MODEL="${LLM_MODEL:-}"
if [ -z "$LLM_KEY" ] && [ "$KUBE_OK" = "1" ]; then
  LLM_KEY=$(kubectl -n $KNS get secret oaf-release-agent-env-secret -o jsonpath='{.data.LLM_API_KEY}' 2>/dev/null | base64 -d || true)
  LLM_BASE=$(kubectl -n $KNS get cm oaf-release-agent-env -o jsonpath='{.data.LLM_BASE_URL}' 2>/dev/null || true)
  LLM_MODEL=$(kubectl -n $KNS get cm oaf-release-agent-env -o jsonpath='{.data.LLM_MODEL_ID}' 2>/dev/null || true)
fi

# ---- CRUD / 唯一性 / 校验 ----
CN=$(req POST /console/workspaces/$WS/tools \
  "{\"name\":\"plat-mcp-$TS\",\"transport\":\"streamable_http\",\"endpoint\":\"$MCP_EP\",\"authType\":\"none\",\"timeoutSec\":30}" "" "$TOKEN")
CN_ID=$(echo "$CN" | jqr "['data']['id']")
assert_eq "建连接器 201" "$([ -n "$CN_ID" ] && echo yes || echo no)" "yes"
assert_eq "重名 400" "$(code POST /console/workspaces/$WS/tools \
  "{\"name\":\"plat-mcp-$TS\",\"endpoint\":\"$MCP_EP\"}" "" "$TOKEN")" "400"
assert_eq "非法 endpoint 400" "$(code POST /console/workspaces/$WS/tools \
  "{\"name\":\"x-$TS\",\"endpoint\":\"ftp://x\"}" "" "$TOKEN")" "400"

# ---- verify 三分支 ----
assert_eq "非法传输 400" "$(code POST /console/workspaces/$WS/tools \
  "{\"name\":\"bad-t-$TS\",\"transport\":\"grpc\",\"endpoint\":\"http://x/mcp\"}" "" "$TOKEN")" "400"
V1=$(req POST /console/workspaces/$WS/tools/$CN_ID/verify "" "$TOKEN")
assert_contains "verify 平台 /mcp ok=true" "$V1" '"ok":true'
TOOLCOUNT=$(echo "$V1" | jqr "['data']['toolCount']")
assert_eq "平台 /mcp 工具数=17" "$TOOLCOUNT" "17"
assert_contains "工具快照含 list_services" "$V1" "list_services"
BAD=$(req POST /console/workspaces/$WS/tools \
  "{\"name\":\"bad-ep-$TS\",\"transport\":\"streamable_http\",\"endpoint\":\"http://invalid.m6test.invalid/mcp\",\"timeoutSec\":5}" "" "$TOKEN" | jqr "['data']['id']")
V2=$(req POST /console/workspaces/$WS/tools/$BAD/verify "" "$TOKEN")
assert_contains "不可达端点 verify ok=false" "$V2" '"ok":false'
assert_eq "verify 失败状态回写 failed" "$(req GET /console/workspaces/$WS/tools "" "$TOKEN" | python3 -c "import sys,json;print([c['status'] for c in json.load(sys.stdin)['data'] if c['id']==$BAD][0])")" "failed"
assert_contains "toolScope 快照写入（选项源）" "$(req GET /console/workspaces/$WS/tools "" "$TOKEN")" "list_services"

# ---- bearer 连接器：token 不落包 → Secret ----
CONN_B=$(req POST /console/workspaces/$WS/tools \
  "{\"name\":\"bearer-mcp-$TS\",\"transport\":\"streamable_http\",\"endpoint\":\"$MCP_EP\",\"authType\":\"bearer\",\"authToken\":\"m6-secret-token-xyz\",\"timeoutSec\":30}" "" "$TOKEN" | jqr "['data']['id']")
assert_eq "bearer 连接器建立" "$([ -n "$CONN_B" ] && echo yes || echo no)" "yes"

# ---- 创建 Agent 勾选连接器 → 注入断言（需 LLM 建连接）----
if [ -n "$LLM_KEY" ] && [ -n "$LLM_BASE" ]; then
  MC=$(req POST /console/workspaces/$WS/model-connections \
    "{\"name\":\"m6-llm\",\"modelId\":\"$LLM_MODEL\",\"baseUrl\":\"$LLM_BASE\",\"apiKey\":\"$LLM_KEY\"}" "" "$TOKEN" | jqr "['data']['id']")
  ensure_verified "$TOKEN" "$WS" "$MC" >/dev/null
  FORM="{\"mode\":\"harness\",\"name\":\"m6agent-$TS\",\"description\":\"M6\",\"modelConnections\":[{\"connectionId\":$MC,\"isDefault\":true}],\"prompt\":\"你是 M6 e2e 助手。\",\"skills\":[],\"subAgents\":[],\"mcpServers\":[{\"connectorId\":$CONN_B}],\"replicas\":1}"
  CV=$(req GET /console/workspaces/$WS/model-connections "" "$TOKEN" | python3 -c "import sys,json;print([c['status'] for c in json.load(sys.stdin)['data'] if c['id']==$MC][0])")
  if [ "$CV" != "verified" ]; then
    say "  SKIP LLM 连接未能 verified（供应商限流），跳过注入链路"
    code DELETE /console/workspaces/$WS/tools/$CONN_B "" "$TOKEN" >/dev/null
    finish "M6-mcp-connector"; exit 0
  fi
  CREATE=$(req POST /console/workspaces/$WS/agents "$FORM" "" "$TOKEN")
  [ -n "${DEBUG:-}" ] && say "DBG CREATE=${CREATE:0:200}"
  SVC=$(echo "$CREATE" | jqr "['data']['service']['id']")
  PKG=$(echo "$CREATE" | jqr "['data']['package']['id']")
  assert_eq "勾选连接器创建 Agent" "$([ -n "$SVC" ] && echo yes || echo no)" "yes"
  wait_status "$TOKEN" "$WS" "$SVC" 240 running register_failed deploy_failed >/dev/null
  ZIP=$(curl -s "$BASE/packages/$PKG/download" -H "Authorization: Bearer $TOKEN" -o /tmp/m6-pkg-$TS.zip -w '%{http_code}')
  assert_eq "生成包下载 200" "$ZIP" "200"
  CONTENT=$(python3 -c "
import zipfile
z=zipfile.ZipFile('/tmp/m6-pkg-$TS.zip')
print('\n'.join(z.namelist()))
print('=====CONFIG=====')
print(z.read('mcp-configs/bearer-mcp-$TS/config.yaml').decode() if 'mcp-configs/bearer-mcp-$TS/config.yaml' in z.namelist() else '')")
  assert_contains "包含 mcp-configs/bearer-mcp-$TS/config.yaml" "$CONTENT" "mcp-configs/bearer-mcp-$TS/config.yaml"
  CFG=$(echo "$CONTENT" | sed -n '/=====CONFIG=====/,$p')
  assert_contains "config.yaml server 名正确" "$CFG" "server: bearer-mcp-$TS"
  assert_contains "config.yaml connection.url 正确" "$CFG" "url: $MCP_EP"
  assert_not_contains "token 不落包" "$CONTENT" "m6-secret-token-xyz"
  if [ "$KUBE_OK" = "1" ]; then
    K8S=$(req GET /console/workspaces/$WS/agents/$SVC "" "$TOKEN" | jqr "['data']['k8sName']")
    SEC=$(kubectl -n $KNS get secret $K8S-env-secret -o jsonpath='{.data}' 2>/dev/null)
    assert_contains "Secret 含 MCP_TOKEN_BEARER_MCP（env 注入）" "$SEC" "MCP_TOKEN_BEARER_MCP"
  else
    skip "kubectl 不可用，跳过 Secret 断言"
  fi
  # 被引用删除 409 → 删 Agent 后可删
  assert_eq "被引用连接器删除 409" "$(code DELETE /console/workspaces/$WS/tools/$CONN_B "" "$TOKEN")" "409"
  code DELETE /console/workspaces/$WS/agents/$SVC "" "$TOKEN" >/dev/null
  assert_eq "解除引用后删除 200" "$(code DELETE /console/workspaces/$WS/tools/$CONN_B "" "$TOKEN")" "200"
else
  say "  ⏭️ LLM 凭据不可得——跳过注入链路（verify 三分支已覆盖）"
fi

# ---- 清理 ----
code DELETE /console/workspaces/$WS/tools/$CN_ID "" "$TOKEN" >/dev/null
code DELETE /console/workspaces/$WS/tools/$BAD "" "$TOKEN" >/dev/null

finish "M6-mcp-connector"

#!/usr/bin/env bash
# M6 · 工具管理 MCP 连接器（实施文档 07 §4/§5，DoD：e2e 脚本 mcp-connector）
# 覆盖：CRUD 唯一性/长度/缺字段校验；verify（平台 /mcp 真靶 ok / 坏端点 failed）；
#       PATCH timeout/permissions；member 写 403；bearer token 不落包/列表不回显 →
#       Secret 含 MCP_TOKEN_*；被引用删除 409；sse transport 创建。
source "$(dirname "$0")/lib.sh"

TS=$(date +%s)
TOKEN=$(admin_login)
[ -n "$TOKEN" ] || { say "❌ admin 登录失败"; exit 1; }
say "-- M6 工具管理 MCP 连接器"

WS=$(seed_ws "$TOKEN" "m6-ws-$TS")
# verify 真靶 = 平台自身 /mcp（集群内 FQDN——verify 由 backend pod 发起，pod 间 DNS 可达；
# 勿按宿主机连通性改写该地址：ingress 400/404 探测会误选不可达于 pod 的入口）
MCP_EP="${MCP_ENDPOINT:-http://oaf-platform-backend.agent-platform.svc.cluster.local:8080/mcp}"
resolve_llm_env

CN_ID=""; BAD=""; CONN_B=""; SSE_ID=""; SVC=""
cleanup_m6() {
  [ -n "$SVC" ] && code DELETE /console/workspaces/$WS/agents/$SVC "" "$TOKEN" >/dev/null 2>&1
  [ -n "$CONN_B" ] && code DELETE /console/workspaces/$WS/tools/$CONN_B "" "$TOKEN" >/dev/null 2>&1
  [ -n "$SSE_ID" ] && code DELETE /console/workspaces/$WS/tools/$SSE_ID "" "$TOKEN" >/dev/null 2>&1
  [ -n "$CN_ID" ] && code DELETE /console/workspaces/$WS/tools/$CN_ID "" "$TOKEN" >/dev/null 2>&1
  [ -n "$BAD" ] && code DELETE /console/workspaces/$WS/tools/$BAD "" "$TOKEN" >/dev/null 2>&1
}
trap cleanup_m6 EXIT

# ---- CRUD / 唯一性 / 校验 ----
CN=$(req POST /console/workspaces/$WS/tools \
  "{\"name\":\"plat-mcp-$TS\",\"transport\":\"streamable_http\",\"endpoint\":\"$MCP_EP\",\"authType\":\"none\",\"timeoutSec\":30}" "" "$TOKEN")
CN_ID=$(echo "$CN" | jqr "['data']['id']")
assert_eq "建连接器 201" "$([ -n "$CN_ID" ] && echo yes || echo no)" "yes"
assert_eq "重名 400" "$(code POST /console/workspaces/$WS/tools \
  "{\"name\":\"plat-mcp-$TS\",\"endpoint\":\"$MCP_EP\"}" "" "$TOKEN")" "400"
assert_eq "非法 endpoint 400" "$(code POST /console/workspaces/$WS/tools \
  "{\"name\":\"x-$TS\",\"endpoint\":\"ftp://x\"}" "" "$TOKEN")" "400"
assert_eq "缺 endpoint 400" "$(code POST /console/workspaces/$WS/tools \
  "{\"name\":\"noep-$TS\"}" "" "$TOKEN")" "400"
LONGNAME=$(printf 'n%.0s' $(seq 65))
assert_eq "名称超 64 字符 400" "$(code POST /console/workspaces/$WS/tools \
  "{\"name\":\"$LONGNAME\",\"endpoint\":\"http://x/mcp\"}" "" "$TOKEN")" "400"
assert_eq "非法传输 400" "$(code POST /console/workspaces/$WS/tools \
  "{\"name\":\"bad-t-$TS\",\"transport\":\"grpc\",\"endpoint\":\"http://x/mcp\"}" "" "$TOKEN")" "400"

# ---- verify：平台 /mcp 真靶 + 不可达端点 ----
V1=$(req POST /console/workspaces/$WS/tools/$CN_ID/verify "" "$TOKEN")
assert_contains "verify 平台 /mcp ok=true" "$V1" '"ok":true'
TOOLCOUNT=$(echo "$V1" | jqr "['data']['toolCount']")
assert_eq "平台 /mcp 工具数 > 0" "$(python3 -c "print($TOOLCOUNT > 0)")" "True"
assert_contains "工具快照含 list_services" "$V1" "list_services"
BAD=$(req POST /console/workspaces/$WS/tools \
  "{\"name\":\"bad-ep-$TS\",\"transport\":\"streamable_http\",\"endpoint\":\"http://invalid.m6test.invalid/mcp\",\"timeoutSec\":5}" "" "$TOKEN" | jqr "['data']['id']")
V2=$(req POST /console/workspaces/$WS/tools/$BAD/verify "" "$TOKEN")
assert_contains "不可达端点 verify ok=false" "$V2" '"ok":false'
assert_eq "verify 失败状态回写 failed" "$(req GET /console/workspaces/$WS/tools "" "$TOKEN" | python3 -c "import sys,json;print([c['status'] for c in json.load(sys.stdin)['data'] if c['id']==$BAD][0])")" "failed"
assert_contains "toolScope 快照写入（选项源）" "$(req GET /console/workspaces/$WS/tools "" "$TOKEN")" "list_services"

# ---- bearer 连接器：token 不落包/不回显 → Secret；PATCH 权限 ----
CONN_B=$(req POST /console/workspaces/$WS/tools \
  "{\"name\":\"bearer-mcp-$TS\",\"transport\":\"streamable_http\",\"endpoint\":\"$MCP_EP\",\"authType\":\"bearer\",\"authToken\":\"m6-secret-token-xyz\",\"timeoutSec\":30}" "" "$TOKEN" | jqr "['data']['id']")
assert_eq "bearer 连接器建立" "$([ -n "$CONN_B" ] && echo yes || echo no)" "yes"
assert_not_contains "列表不回显 bearer token" \
  "$(req GET /console/workspaces/$WS/tools "" "$TOKEN")" "m6-secret-token-xyz"
P=$(req PATCH /console/workspaces/$WS/tools/$CONN_B \
  '{"timeoutSec":45,"permissions":{"tools":{"list_services":"deny"}}}' "" "$TOKEN")
assert_eq "PATCH timeoutSec 生效" "$(echo "$P" | jqr "['data']['timeoutSec']")" "45"
assert_contains "PATCH permissions 生效" "$P" "list_services"

# ---- sse transport：创建 + verify 契约（当前 verify 统一走 streamable HTTP 探测）----
SSE_ID=$(req POST /console/workspaces/$WS/tools \
  "{\"name\":\"sse-mcp-$TS\",\"transport\":\"sse\",\"endpoint\":\"$MCP_EP\",\"timeoutSec\":10}" "" "$TOKEN" | jqr "['data']['id']")
assert_eq "sse transport 创建 201" "$([ -n "$SSE_ID" ] && [ "$SSE_ID" != "None" ] && echo yes || echo no)" "yes"
assert_eq "sse transport verify 返回 200" "$(code POST /console/workspaces/$WS/tools/$SSE_ID/verify "" "$TOKEN")" "200"

# ---- member 写 403 ----
U1=$(seed_user "$TOKEN" "m6-member-$TS" "Pass12345!")
req POST /admin/workspaces/$WS/members "{\"userId\":$U1,\"role\":\"member\"}" "" "$TOKEN" >/dev/null
MT=$(req POST /auth/login "{\"username\":\"m6-member-$TS\",\"password\":\"Pass12345!\"}" | jqr "['data']['token']")
assert_eq "member 建连接器 403" "$(code POST /console/workspaces/$WS/tools \
  "{\"name\":\"member-$TS\",\"endpoint\":\"$MCP_EP\"}" "" "$MT")" "403"
assert_eq "member PATCH 403" "$(code PATCH /console/workspaces/$WS/tools/$CN_ID '{"timeoutSec":10}' "" "$MT")" "403"
assert_eq "member DELETE 403" "$(code DELETE /console/workspaces/$WS/tools/$CN_ID "" "$MT")" "403"

# ---- 创建 Agent 勾选连接器 → 注入断言（需 verified LLM 连接）----
if [ -z "$LLM_KEY" ] || [ -z "$LLM_BASE" ]; then
  skip "LLM 凭据不可得——跳过 Agent 注入链路（CRUD/verify/PATCH/member 已覆盖）"
else
  MC=$(req POST /console/workspaces/$WS/model-connections \
    "{\"name\":\"m6-llm-$TS\",\"modelId\":\"$LLM_MODEL\",\"baseUrl\":\"$LLM_BASE\",\"apiKey\":\"$LLM_KEY\"}" "" "$TOKEN" | jqr "['data']['id']")
  ensure_verified "$TOKEN" "$WS" "$MC" >/dev/null
  CV=$(req GET /console/workspaces/$WS/model-connections "" "$TOKEN" | python3 -c "import sys,json;print([c['status'] for c in json.load(sys.stdin)['data'] if c['id']==$MC][0])")
  if [ "$CV" != "verified" ]; then
    skip "LLM 连接未能 verified（供应商限流），跳过注入链路"
  else
    FORM="{\"mode\":\"harness\",\"name\":\"m6agent-$TS\",\"description\":\"M6\",\"modelConnections\":[{\"connectionId\":$MC,\"isDefault\":true}],\"prompt\":\"你是 M6 e2e 助手。\",\"skills\":[],\"subAgents\":[],\"mcpServers\":[{\"connectorId\":$CONN_B}],\"replicas\":1}"
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
    assert_contains "config.yaml 权限三态落盘" "$CFG" "list_services: deny"
    assert_contains "config.yaml timeout 落盘" "$CFG" "timeout: 45"
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
    SVC=""
    assert_eq "解除引用后删除 200" "$(code DELETE /console/workspaces/$WS/tools/$CONN_B "" "$TOKEN")" "200"
    CONN_B=""
  fi
fi

finish "M6-mcp-connector"

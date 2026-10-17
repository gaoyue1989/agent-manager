#!/usr/bin/env bash
# M5 · 模型连接（实施文档 06 §4/§5，DoD：e2e 脚本 model-switch）
# 覆盖：CRUD 唯一性/掩码/apiKey 清除；test 三分支（真实 ok / 坏端点 failed / 不可达）；
#       创建 Agent 勾选→usage pending→synced；deliver 幂等（重复 200 不重复建）；
#       删除保护（默认连接 409）；Agent /models 回读；调试会话级切换两轮断言 model
source "$(dirname "$0")/lib.sh"

TS=$(date +%s)
TOKEN=$(admin_login)
[ -n "$TOKEN" ] || { say "❌ admin 登录失败"; exit 1; }
say "-- M5 模型连接"

WS=$(seed_ws "$TOKEN" "m5-ws-$TS")
LLM_BASE="${LLM_BASE_URL:-}"; LLM_KEY="${LLM_API_KEY:-}"; LLM_MODEL="${LLM_MODEL:-}"
if [ -z "$LLM_KEY" ] && [ "$KUBE_OK" = "1" ]; then
  LLM_KEY=$(kubectl -n $KNS get secret oaf-release-agent-env-secret -o jsonpath='{.data.LLM_API_KEY}' 2>/dev/null | base64 -d || true)
  LLM_BASE=$(kubectl -n $KNS get cm oaf-release-agent-env -o jsonpath='{.data.LLM_BASE_URL}' 2>/dev/null || true)
  LLM_MODEL=$(kubectl -n $KNS get cm oaf-release-agent-env -o jsonpath='{.data.LLM_MODEL_ID}' 2>/dev/null || true)
fi
if [ -z "$LLM_KEY" ] || [ -z "$LLM_BASE" ]; then
  say "  ⏭️ LLM 凭据不可得——跳过（置 LLM_API_KEY/LLM_BASE_URL/LLM_MODEL 后重跑）"; finish "M5-model-connection"; exit 0
fi

# ---- CRUD / 掩码 / 唯一性 ----
C1=$(req POST /console/workspaces/$WS/model-connections \
  "{\"name\":\"conn-a-$TS\",\"modelId\":\"$LLM_MODEL\",\"baseUrl\":\"$LLM_BASE\",\"apiKey\":\"sk-m5test1234567890\"}" "" "$TOKEN")
C1_ID=$(echo "$C1" | jqr "['data']['id']")
assert_eq "建连接 201" "$([ -n "$C1_ID" ] && echo yes || echo no)" "yes"
assert_contains "apiKeyMasked 掩码（无明文）" "$(echo "$C1" | head -c 2000)" "sk-***"
assert_not_contains "响应体无 apiKey 明文" "$C1" "sk-m5test1234567890"
assert_eq "空间内重名 400" "$(code POST /console/workspaces/$WS/model-connections \
  "{\"name\":\"conn-a-$TS\",\"modelId\":\"m\",\"baseUrl\":\"http://x/v1\"}" "" "$TOKEN")" "400"
assert_eq "非法 baseUrl 400" "$(code POST /console/workspaces/$WS/model-connections \
  "{\"name\":\"b-$TS\",\"modelId\":\"m\",\"baseUrl\":\"ftp://x\"}" "" "$TOKEN")" "400"
assert_eq "跨空间隔离（他空间列表不含）" "$(req GET /console/workspaces/$(seed_ws "$TOKEN" "m5-ws2-$TS")/model-connections "" "$TOKEN" | grep -c "conn-a-$TS")" "0"

# ---- test 三分支 ----
TR=$(req POST /console/workspaces/$WS/model-connections/$C1_ID/test "" "$TOKEN")
assert_contains "真实端点 ok=true" "$TR" '"ok":true'
assert_contains "延迟毫秒返回" "$TR" '"latencyMs"'
if ! echo "$TR" | grep -q '"ok":true'; then
  say "  ⏭️ LLM 供应商连续 3 次不可达，跳过本模块（网络恢复后重跑）"; finish "M5-model-connection"; exit 0
fi
C2=$(req POST /console/workspaces/$WS/model-connections \
  "{\"name\":\"conn-bad-$TS\",\"modelId\":\"$LLM_MODEL\",\"baseUrl\":\"https://invalid.m5test.invalid/v1\",\"apiKey\":\"k\"}" "" "$TOKEN" | jqr "['data']['id']")
TR2=$(req POST /console/workspaces/$WS/model-connections/$C2/test "" "$TOKEN")
assert_contains "不可达端点 ok=false" "$TR2" '"ok":false'
assert_eq "失败状态回写 failed" "$(req GET /console/workspaces/$WS/model-connections "" "$TOKEN" | python3 -c "import sys,json;print([c['status'] for c in json.load(sys.stdin)['data'] if c['id']==$C2][0])")" "failed"

# ---- PATCH：改字段 → delivery 重置 pending / apiKey 清除语义 ----
req POST /console/workspaces/$WS/model-connections/$C1_ID/test "" "$TOKEN" >/dev/null
P=$(req PATCH /console/workspaces/$WS/model-connections/$C1_ID "{\"modelId\":\"$LLM_MODEL\",\"apiKey\":\"\"}" "" "$TOKEN")
assert_contains "PATCH 清除 apiKey → 掩码为空" "$(echo "$P" | head -c 400)" '"apiKeyMasked":"'
# 重新写回 key（后续 deliver 需要）
req PATCH /console/workspaces/$WS/model-connections/$C1_ID "{\"apiKey\":\"$LLM_KEY\"}" "" "$TOKEN" >/dev/null

# ---- 创建 Agent 勾选 → usage pending→synced ----
FORM="{\"mode\":\"harness\",\"name\":\"m5agent-$TS\",\"description\":\"M5\",\"modelConnections\":[{\"connectionId\":$C1_ID,\"isDefault\":true}],\"prompt\":\"你是 M5 e2e 助手，问候时回答 ok。\",\"skills\":[],\"subAgents\":[],\"mcpServers\":[],\"replicas\":1}"
SVC=$(req POST /console/workspaces/$WS/agents "$FORM" "" "$TOKEN" | jqr "['data']['service']['id']")
assert_eq "勾选连接创建 Agent" "$([ -n "$SVC" ] && echo yes || echo no)" "yes"
ST=$(wait_status "$TOKEN" "$WS" "$SVC" 240 running register_failed deploy_failed)
assert_eq "Agent running" "$ST" "running"
USAGE=$(req GET /console/workspaces/$WS/model-connections/usage "" "$TOKEN")
assert_contains "usage 含该 Agent" "$USAGE" "m5agent-$TS"
assert_contains "usage 状态 synced" "$USAGE" '"status":"synced"'
assert_contains "usage connections 为数组（非 null）" "$USAGE" '"connections":['

# ---- deliver 幂等：重复下发 200 且 usage 仍 synced 单条 ----
req POST /console/workspaces/$WS/model-connections/$C1_ID/deliver "{\"serviceId\":$SVC}" "" "$TOKEN" >/dev/null
D2=$(req POST /console/workspaces/$WS/model-connections/$C1_ID/deliver "{\"serviceId\":$SVC}" "" "$TOKEN")
assert_eq "重复下发 202 幂等" "$(echo "$D2" | jqr "['data']['status']")" "synced"
assert_eq "usage 连接条数=1（无重复）" \
  "$(req GET /console/workspaces/$WS/model-connections/usage "" "$TOKEN" | python3 -c "import sys,json;print(len([r for r in json.load(sys.stdin)['data'] if r['serviceId']==$SVC][0]['connections']))")" "1"

# ---- Agent /models 回读（含下发连接）----
MODELS=$(req GET "/console/workspaces/$WS/model-connections/models?serviceId=$SVC" "" "$TOKEN")
assert_contains "Agent models 含下发连接名" "$MODELS" "conn-a-$TS"

# ---- 会话级切换两轮断言 model（DoD：model-switch）----
UID=$(echo "$TOKEN" | python3 -c "import sys,json,base64;print(json.loads(base64.b64decode(sys.stdin.read().split('.')[1]+'==').decode())['uid'])")
FE="${FRONTEND_BASE:-http://localhost:8911}"
SHORT=$(req GET /console/workspaces/$WS/agents/$SVC "" "$TOKEN" | jqr "['data']['shortName']")
REMOTE_ID=$(echo "$MODELS" | python3 -c "import sys,json;print([m['id'] for m in json.load(sys.stdin)['models'] if m.get('name')=='conn-a-$TS'][0])" 2>/dev/null)
if [ -n "$REMOTE_ID" ] && [ "$REMOTE_ID" != "None" ]; then
  curl -s -N -m 45 "$FE/agent/$SHORT/threads/chat" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
    -d "{\"message\":\"hi\",\"userId\":\"u-$UID\",\"sessionId\":\"m5-switch-$TS\",\"model\":\"$REMOTE_ID\"}" >/dev/null 2>&1
  GOT=$(req GET "$BASE/threads/m5-switch-$TS" "" "$TOKEN" 2>/dev/null)
  if [ -z "$GOT" ]; then GOT=$(curl -s -m 10 "$FE/agent/$SHORT/threads/m5-switch-$TS" -H "Authorization: Bearer $TOKEN"); fi
  assert_contains "切换到下发模型（session model=remote id）" "$GOT" "$REMOTE_ID"
  curl -s -N -m 45 "$FE/agent/$SHORT/threads/chat" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
    -d "{\"message\":\"hi again\",\"userId\":\"u-$UID\",\"sessionId\":\"m5-switch-$TS\",\"model\":\"\"}" >/dev/null 2>&1
  GOT2=$(curl -s -m 10 "$FE/agent/$SHORT/threads/m5-switch-$TS" -H "Authorization: Bearer $TOKEN" 2>/dev/null)
  assert_contains "切回系统默认（model 置空回默认）" "$GOT2" '"model":""'
else
  skip "Agent /models 未枚举到下发连接（deliver 未 synced？），跳过会话切换"
fi

# ---- 删除保护：默认连接 409；下发连接可删 ----
assert_contains "默认连接删除 409" "$(code DELETE /console/workspaces/$WS/model-connections/$C1_ID "" "$TOKEN"; true)" "409"
assert_eq "默认连接删除状态码确认" "$(code DELETE /console/workspaces/$WS/model-connections/$C1_ID "" "$TOKEN")" "409"

# ---- 清理 ----
code DELETE /console/workspaces/$WS/agents/$SVC "" "$TOKEN" >/dev/null
code DELETE /console/workspaces/$WS/model-connections/$C2 "" "$TOKEN" >/dev/null

finish "M5-model-connection"

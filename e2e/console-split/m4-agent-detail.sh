#!/usr/bin/env bash
# M4 · Agent 详情六 Tab（实施文档 05 §4/§5）
# 覆盖：详情聚合（pods/events/envSecretKeys）/ 生命周期动作状态机闭环（unpublish→publish→
#       republish→register）/ 版本 Tab（current 标注/upgrade）；member 只读矩阵（自有空间，
#       非 default——决议 2 下 default 对普通用户不可见，写 403 断言须在可见空间做）
# 调试 Tab SSE 与 HITL 在 M3 覆盖；技能/凭证 Tab 在 M5/M8 覆盖。
source "$(dirname "$0")/lib.sh"

TS=$(date +%s)
TOKEN=$(admin_login)
[ -n "$TOKEN" ] || { say "❌ admin 登录失败"; exit 1; }
say "-- M4 Agent 详情"

WS=$(seed_ws "$TOKEN" "m4-ws-$TS")
U1=$(seed_user "$TOKEN" "m4-member-$TS" "Pass12345!")
req POST /admin/workspaces/$WS/members "{\"userId\":$U1,\"role\":\"member\"}" "" "$TOKEN" >/dev/null
MT=$(req POST /auth/login "{\"username\":\"m4-member-$TS\",\"password\":\"Pass12345!\"}" | jqr "['data']['token']")
assert_eq "member 登录" "$([ -n "$MT" ] && echo yes || echo no)" "yes"

# 前置：LLM 连接 + 测试 Agent（无 LLM 时降级：借用 default 空间服务做只读断言）
LLM_BASE="${LLM_BASE_URL:-}"; LLM_KEY="${LLM_API_KEY:-}"; LLM_MODEL="${LLM_MODEL:-}"
if [ -z "$LLM_KEY" ] && [ "$KUBE_OK" = "1" ]; then
  LLM_KEY=$(kubectl -n $KNS get secret oaf-release-agent-env-secret -o jsonpath='{.data.LLM_API_KEY}' 2>/dev/null | base64 -d || true)
  LLM_BASE=$(kubectl -n $KNS get cm oaf-release-agent-env -o jsonpath='{.data.LLM_BASE_URL}' 2>/dev/null || true)
  LLM_MODEL=$(kubectl -n $KNS get cm oaf-release-agent-env -o jsonpath='{.data.LLM_MODEL_ID}' 2>/dev/null || true)
fi
TEST_SVC=""
if [ -n "$LLM_KEY" ] && [ -n "$LLM_BASE" ]; then
  MC=$(req POST /console/workspaces/$WS/model-connections \
    "{\"name\":\"m4-llm-$TS\",\"modelId\":\"$LLM_MODEL\",\"baseUrl\":\"$LLM_BASE\",\"apiKey\":\"$LLM_KEY\"}" "" "$TOKEN" | jqr "['data']['id']")
  req POST /console/workspaces/$WS/model-connections/$MC/test "" "$TOKEN" >/dev/null
  TEST_SVC=$(req POST /console/workspaces/$WS/agents \
    "{\"mode\":\"harness\",\"name\":\"m4lifecycle-$TS\",\"description\":\"M4 lifecycle e2e\",\"modelConnections\":[{\"connectionId\":$MC,\"isDefault\":true}],\"prompt\":\"你是 M4 e2e 助手。\",\"skills\":[],\"subAgents\":[],\"mcpServers\":[],\"replicas\":1}" \
    "" "$TOKEN" | jqr "['data']['service']['id']")
  ST=$(wait_status "$TOKEN" "$WS" "$TEST_SVC" 240 running register_failed deploy_failed)
  assert_eq "测试 Agent running" "$ST" "running"
fi

# ---- 详情聚合 ----
DETAIL=$(req GET /console/workspaces/$WS/agents/$TEST_SVC "" "$TOKEN")
assert_contains "详情含 pods 数组" "$DETAIL" '"pods"'
assert_contains "详情含 events 时间线" "$DETAIL" '"events"'
assert_contains "详情含 envSecretKeys 掩码视图" "$DETAIL" '"envSecretKeys"'
assert_eq "跨空间访问 404" "$(code GET /console/workspaces/999999/agents/$TEST_SVC "" "$TOKEN")" "404"
assert_eq "跨空间版本 404" "$(code GET /console/workspaces/999999/agents/$TEST_SVC/versions "" "$TOKEN")" "404"

# ---- 生命周期动作状态机闭环 ----
assert_eq "unpublish → stopped" \
  "$(req POST /console/workspaces/$WS/agents/$TEST_SVC/unpublish "" "$TOKEN" | jqr "['data']['status']")" "stopped"
assert_eq "publish（重新上线）受理" \
  "$(req POST /console/workspaces/$WS/agents/$TEST_SVC/publish "" "$TOKEN" | jqr "['data']['status']")" "deploying"
ST=$(wait_status "$TOKEN" "$WS" "$TEST_SVC" 240 running register_failed deploy_failed)
assert_eq "publish 回 running" "$ST" "running"
assert_eq "republish 受理 deploying" \
  "$(req POST /console/workspaces/$WS/agents/$TEST_SVC/republish "{}" "" "$TOKEN" | jqr "['data']['status']")" "deploying"
ST=$(wait_status "$TOKEN" "$WS" "$TEST_SVC" 240 running register_failed deploy_failed)
assert_eq "republish 回 running" "$ST" "running"
assert_eq "register 动作受理" "$(code POST /console/workspaces/$WS/agents/$TEST_SVC/register "" "$TOKEN")" "200"

# ---- 版本 Tab ----
VERSIONS=$(req GET /console/workspaces/$WS/agents/$TEST_SVC/versions "" "$TOKEN")
assert_contains "版本列表含 current 标注" "$VERSIONS" '"current":true'
assert_contains "版本列表含 sourcePackageId（溯源）" "$VERSIONS" '"sourcePackageId"'
CUR=$(echo "$VERSIONS" | python3 -c "import sys,json;print([p['id'] for p in json.load(sys.stdin)['data'] if p['current']][0])")
OTHER=$(echo "$VERSIONS" | python3 -c "import sys,json;print([p['id'] for p in json.load(sys.stdin)['data'] if not p['current']][0])" 2>/dev/null || true)
if [ -n "$OTHER" ] && [ "$OTHER" != "None" ]; then
  assert_eq "升级到历史版本 202" "$(code POST /console/workspaces/$WS/agents/$TEST_SVC/versions/$OTHER/upgrade "" "$TOKEN")" "202"
  wait_status "$TOKEN" "$WS" "$TEST_SVC" 240 running register_failed deploy_failed >/dev/null
  CUR2=$(req GET /console/workspaces/$WS/agents/$TEST_SVC/versions "" "$TOKEN" | python3 -c "import sys,json;print([p['id'] for p in json.load(sys.stdin)['data'] if p['current']][0])")
  assert_eq "升级后 current 切换到历史包" "$CUR2" "$OTHER"
else
  skip "单版本链无历史包，upgrade 断言由 M3 编辑链路覆盖"
fi

# ---- member 只读矩阵（member 在本空间可见：读 200 / 写 403）----
assert_eq "member 详情可读" "$(code GET /console/workspaces/$WS/agents/$TEST_SVC "" "$MT")" "200"
assert_eq "member unpublish 403" "$(code POST /console/workspaces/$WS/agents/$TEST_SVC/unpublish "" "$MT")" "403"
assert_eq "member republish 403" "$(code POST /console/workspaces/$WS/agents/$TEST_SVC/republish "{}" "" "$MT")" "403"
assert_eq "member register 403" "$(code POST /console/workspaces/$WS/agents/$TEST_SVC/register "" "$MT")" "403"
assert_eq "member 版本升级 403" "$(code POST /console/workspaces/$WS/agents/$TEST_SVC/versions/$CUR/upgrade "" "$MT")" "403"
assert_eq "member DELETE 403" "$(code DELETE /console/workspaces/$WS/agents/$TEST_SVC "" "$MT")" "403"

# ---- 清理 ----
code DELETE /console/workspaces/$WS/agents/$TEST_SVC "" "$TOKEN" >/dev/null

finish "M4-agent-detail"

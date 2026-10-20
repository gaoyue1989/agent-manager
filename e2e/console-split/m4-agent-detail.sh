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

# 前置：LLM 连接 + 测试 Agent（CI 用 mock LLM）
resolve_llm_env
if [ -z "$LLM_KEY" ] || [ -z "$LLM_BASE" ]; then
  skip "LLM 凭据不可得，M4 生命周期链路需 verified 连接"; finish "M4-agent-detail"; exit 2
fi
MC=$(req POST /console/workspaces/$WS/model-connections \
  "{\"name\":\"m4-llm-$TS\",\"modelId\":\"$LLM_MODEL\",\"baseUrl\":\"$LLM_BASE\",\"apiKey\":\"$LLM_KEY\"}" "" "$TOKEN" | jqr "['data']['id']")
ensure_verified "$TOKEN" "$WS" "$MC" >/dev/null
CREATE4=$(req POST /console/workspaces/$WS/agents \
  "{\"mode\":\"harness\",\"name\":\"m4lifecycle-$TS\",\"description\":\"M4 lifecycle e2e\",\"modelConnections\":[{\"connectionId\":$MC,\"isDefault\":true}],\"prompt\":\"你是 M4 e2e 助手。\",\"skills\":[],\"subAgents\":[],\"mcpServers\":[],\"replicas\":1}" \
  "" "$TOKEN")
TEST_SVC=$(echo "$CREATE4" | jqr "['data']['service']['id']")
TEST_PKG=$(echo "$CREATE4" | jqr "['data']['package']['id']")
TEST_K8S=$(echo "$CREATE4" | jqr "['data']['service']['k8sName']")
assert_eq "测试 Agent 创建 202" "$([ -n "$TEST_SVC" ] && echo yes || echo no)" "yes"
ST=$(wait_status "$TOKEN" "$WS" "$TEST_SVC" 240 running register_failed deploy_failed)
assert_eq "测试 Agent running" "$ST" "running"

# ---- 详情聚合 ----
DETAIL=$(req GET /console/workspaces/$WS/agents/$TEST_SVC "" "$TOKEN")
assert_contains "详情含 pods 数组" "$DETAIL" '"pods"'
assert_contains "详情含 events 时间线" "$DETAIL" '"events"'
assert_contains "详情含 envSecretKeys 掩码视图" "$DETAIL" '"envSecretKeys"'
assert_contains "详情含注册版本" "$DETAIL" '"registeredVersion"'
assert_eq "跨空间访问 404" "$(code GET /console/workspaces/999999/agents/$TEST_SVC "" "$TOKEN")" "404"
assert_eq "跨空间版本 404" "$(code GET /console/workspaces/999999/agents/$TEST_SVC/versions "" "$TOKEN")" "404"

# ---- 列表过滤（status / keyword）----
assert_contains "列表 status=running 命中" \
  "$(req GET "/console/workspaces/$WS/agents?status=running" "" "$TOKEN")" "$TEST_K8S"
assert_contains "列表 keyword 命中 displayName" \
  "$(req GET "/console/workspaces/$WS/agents?keyword=m4lifecycle-$TS" "" "$TOKEN")" "m4lifecycle-$TS"
assert_eq "列表 keyword 无匹配返回空数组" \
  "$(req GET "/console/workspaces/$WS/agents?keyword=no-such-$TS" "" "$TOKEN" | jqr "['data']")" "[]"

# ---- edit-form 回显（模型连接 / 技能 / 沙箱 / 版本）----
EDITFORM=$(req GET /console/workspaces/$WS/agents/$TEST_SVC/edit-form "" "$TOKEN")
assert_contains "edit-form 回显模型连接" "$EDITFORM" "m4-llm-$TS"
assert_contains "edit-form 含 skills 数组" "$EDITFORM" '"skills":'
assert_contains "edit-form 含 sandbox" "$EDITFORM" '"sandbox":'
assert_contains "edit-form 含包版本" "$EDITFORM" '"version":'

# ---- 非成员普通用户：详情/版本 404（不是 403）----
OUTSIDER=$(seed_user "$TOKEN" "m4-outsider-$TS" "Pass12345!")
OT=$(req POST /auth/login "{\"username\":\"m4-outsider-$TS\",\"password\":\"Pass12345!\"}" | jqr "['data']['token']")
assert_eq "非成员详情 404" "$(code GET /console/workspaces/$WS/agents/$TEST_SVC "" "$OT")" "404"
assert_eq "非成员版本 404" "$(code GET /console/workspaces/$WS/agents/$TEST_SVC/versions "" "$OT")" "404"
assert_eq "非成员 edit-form 404" "$(code GET /console/workspaces/$WS/agents/$TEST_SVC/edit-form "" "$OT")" "404"

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

# ---- 先编辑生成 v1.0.1，制造可升级的历史版本 ----
FORM4="{\"mode\":\"harness\",\"name\":\"m4lifecycle-$TS\",\"description\":\"M4 lifecycle e2e v2\",\"modelConnections\":[{\"connectionId\":$MC,\"isDefault\":true}],\"prompt\":\"你是 M4 e2e 助手 v2。\",\"skills\":[],\"subAgents\":[],\"mcpServers\":[],\"replicas\":1}"
assert_eq "编辑生成 v1.0.1" \
  "$(req PUT /console/workspaces/$WS/agents/$TEST_SVC "$FORM4" "" "$TOKEN" | jqr "['data']['package']['version']")" "1.0.1"
ST=$(wait_status "$TOKEN" "$WS" "$TEST_SVC" 240 running register_failed deploy_failed)
assert_eq "编辑后回 running" "$ST" "running"

# ---- 版本 Tab ----
VERSIONS=$(req GET /console/workspaces/$WS/agents/$TEST_SVC/versions "" "$TOKEN")
assert_contains "版本列表含 current 标注" "$VERSIONS" '"current":true'
assert_contains "版本列表含 sourcePackageId（溯源）" "$VERSIONS" '"sourcePackageId"'
CUR=$(echo "$VERSIONS" | python3 -c "import sys,json;print([p['id'] for p in json.load(sys.stdin)['data'] if p['current']][0])")
OTHER=$(echo "$VERSIONS" | python3 -c "import sys,json;print([p['id'] for p in json.load(sys.stdin)['data'] if not p['current']][0])" 2>/dev/null || true)
if [ -n "$OTHER" ] && [ "$OTHER" != "None" ]; then
  assert_eq "升级到历史版本 200" "$(code POST /console/workspaces/$WS/agents/$TEST_SVC/versions/$OTHER/upgrade "" "$TOKEN")" "200"
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

# ---- 删除副作用：Agent 记录消失、包保留、引用计数回落 ----
assert_eq "删除 Agent 200" "$(code DELETE /console/workspaces/$WS/agents/$TEST_SVC "" "$TOKEN")" "200"
assert_eq "删除后详情 404" "$(code GET /console/workspaces/$WS/agents/$TEST_SVC "" "$TOKEN")" "404"
assert_eq "删除后包保留 200" "$(legacy_code GET /packages/$TEST_PKG)" "200"
assert_eq "删除后包 refCount=0" "$(legacy_req GET /packages/$TEST_PKG | jqr "['data']['package']['refCount']")" "0"
if [ "$KUBE_OK" = "1" ]; then
  kubectl -n $KNS get deploy $TEST_K8S >/dev/null 2>&1 && bad "删除后 Deployment 残留" || ok "删除后 Deployment 清理"
  kubectl -n $KNS get cm $TEST_K8S-env >/dev/null 2>&1 && bad "删除后 ConfigMap 残留" || ok "删除后 ConfigMap 清理"
else
  skip "kubectl 不可用，跳过删除后 K8s 对象清理断言"
fi

finish "M4-agent-detail"

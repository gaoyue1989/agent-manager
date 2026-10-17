#!/usr/bin/env bash
# M1 · 运维侧治理（实施文档 02 §4/§5）
# 覆盖：建号/重名 400/角色变更/禁用登录拦截/重置密码；工作空间 CRUD/配额/删除保护；
#       成员矩阵（重复 409/owner 保护/角色变更）；纳管三态；审计含治理动作
source "$(dirname "$0")/lib.sh"

TS=$(date +%s)
TOKEN=$(admin_login)
[ -n "$TOKEN" ] || { say "❌ admin 登录失败"; exit 1; }
say "-- M1 运维侧治理"

# ---- 用户管理 ----
U1=$(seed_user "$TOKEN" "m1-user-$TS" "Pass12345!")
U2=$(seed_user "$TOKEN" "m1-admin-$TS" "Pass12345!" admin)
assert_eq "建号返回 id" "$([ -n "$U1" ] && echo yes || echo no)" "yes"
assert_eq "重名 400 duplicate" "$(code POST /admin/users "{\"username\":\"m1-user-$TS\",\"password\":\"x\"}" "" "$TOKEN")" "400"
assert_eq "角色变更 admin" "$(req PATCH /admin/users/$U1 "{\"role\":\"admin\"}" "" "$TOKEN" | jqr "['data']['role']")" "admin"
# 重置密码后旧密码失效、新密码可登录
req PATCH /admin/users/$U1 "{\"password\":\"NewPass999\"}" "" "$TOKEN" >/dev/null
assert_eq "旧密码 401" "$(code POST /auth/login '{"username":"m1-user-'$TS'","password":"Pass12345!"}')" "401"
assert_contains "新密码可登录" "$(req POST /auth/login '{"username":"m1-user-'$TS'","password":"NewPass999"}')" '"token"'
# 禁用 → 登录 403（已发 token 的即时撤销见 M0 §7，issue #148）
req PATCH /admin/users/$U2 "{\"status\":\"disabled\"}" "" "$TOKEN" >/dev/null
assert_eq "禁用后登录 403" "$(code POST /auth/login '{"username":"m1-admin-'$TS'","password":"Pass12345!"}')" "403"
# 非法角色/状态 400
assert_eq "非法角色 400" "$(code PATCH /admin/users/$U1 "{\"role\":\"root\"}" "" "$TOKEN")" "400"
assert_eq "非法状态 400" "$(code PATCH /admin/users/$U1 "{\"status\":\"frozen\"}" "" "$TOKEN")" "400"

# ---- 工作空间 CRUD ----
WS=$(seed_ws "$TOKEN" "m1-ws-$TS" "$U1")
assert_eq "建空间返回 id" "$([ -n "$WS" ] && echo yes || echo no)" "yes"
WS_SLUG=$(req GET /admin/workspaces/$WS "" "$TOKEN" | jqr "['data']['slug']")
assert_contains "slug 形态 ws-*" "$WS_SLUG" "ws-"
assert_eq "空名 400" "$(code POST /admin/workspaces "{\"name\":\" \"}" "" "$TOKEN")" "400"
assert_eq "不存在空间 404" "$(code GET /admin/workspaces/999999 "" "$TOKEN")" "404"
# owner 成员关系自动建立 + memberCount 聚合
assert_eq "owner 成员自动建立 memberCount=1" "$(req GET /admin/workspaces/$WS "" "$TOKEN" | jqr "['data']['memberCount']")" "1"
# 停用后拒绝新成员（不影响已有成员访问——控制台层语义）
req PATCH /admin/workspaces/$WS "{\"status\":\"disabled\"}" "" "$TOKEN" >/dev/null
U3=$(seed_user "$TOKEN" "m1-u3-$TS" "Pass12345!")
assert_eq "停用空间加成员 409" "$(code POST /admin/workspaces/$WS/members "{\"userId\":$U3,\"role\":\"member\"}" "" "$TOKEN")" "409"
req PATCH /admin/workspaces/$WS "{\"status\":\"active\"}" "" "$TOKEN" >/dev/null
# 配额更新
req PATCH /admin/workspaces/$WS "{\"quota\":{\"maxAgents\":5}}" "" "$TOKEN" >/dev/null
assert_eq "配额 maxAgents=5" "$(req GET /admin/workspaces/$WS "" "$TOKEN" | jqr "['data']['quota']['maxAgents']")" "5"

# ---- 成员矩阵 ----
assert_eq "加成员 member 201" "$(code POST /admin/workspaces/$WS/members "{\"userId\":$U3,\"role\":\"member\"}" "" "$TOKEN")" "201"
assert_eq "重复加入 409" "$(code POST /admin/workspaces/$WS/members "{\"userId\":$U3,\"role\":\"member\"}" "" "$TOKEN")" "409"
assert_eq "改角色 admin" "$(req PATCH /admin/workspaces/$WS/members/$U3 "{\"role\":\"admin\"}" "" "$TOKEN" | jqr "['message']")" "ok"
assert_contains "成员列表含 U3 admin" "$(req GET /admin/workspaces/$WS/members "" "$TOKEN")" '"role":"admin"'
assert_eq "owner 移除受保护 409" "$(code DELETE /admin/workspaces/$WS/members/$U1 "" "$TOKEN")" "409"
assert_eq "owner 降级受保护 409" "$(code PATCH /admin/workspaces/$WS/members/$U1 "{\"role\":\"member\"}" "" "$TOKEN")" "409"
assert_eq "移除普通成员 200" "$(code DELETE /admin/workspaces/$WS/members/$U3 "" "$TOKEN")" "200"
assert_eq "移除不存在成员 404" "$(code DELETE /admin/workspaces/$WS/members/999999 "" "$TOKEN")" "404"

# ---- 纳管三态 ----
DEF_WS=$(req GET /admin/workspaces "" "$TOKEN" | python3 -c "import sys,json;print(next((w['id'] for w in json.load(sys.stdin)['data'] if w['slug']=='default'),''))")
SVC_ID=$(req GET "/admin/workspaces/$DEF_WS/agents" "" "$TOKEN" | jqr "['data'][0]['id']" 2>/dev/null || true)
if [ -n "$SVC_ID" ] && [ "$SVC_ID" != "None" ]; then
  assert_eq "bind default→业务空间 200" "$(code POST /admin/workspaces/$WS/bind "{\"serviceId\":$SVC_ID}" "" "$TOKEN")" "200"
  assert_eq "跨空间 bind 409" "$(code POST /admin/workspaces/$DEF_WS/bind "{\"serviceId\":$SVC_ID}" "" "$TOKEN" >/dev/null; code POST /admin/workspaces/$(seed_ws "$TOKEN" "m1-ws2-$TS")/bind "{\"serviceId\":$SVC_ID}" "" "$TOKEN")" "409"
  req POST /admin/workspaces/$DEF_WS/bind "{\"serviceId\":$SVC_ID}" "" "$TOKEN" >/dev/null # 还原
else
  skip "无存量服务，跳过 bind 三态"
fi

# ---- 删除保护 / 审计 ----
assert_eq "非空空间删除 409" "$(code DELETE /admin/workspaces/$WS "" "$TOKEN")" "409"
EMPTY_WS=$(seed_ws "$TOKEN" "m1-empty-$TS")
assert_eq "空空间删除 200" "$(code DELETE /admin/workspaces/$EMPTY_WS "" "$TOKEN")" "200"
AUDIT=$(req GET "/admin/audit-logs?page=1&size=100" "" "$TOKEN")
assert_contains "审计含 workspace.create" "$AUDIT" "workspace.create"
assert_contains "审计含 user.create" "$AUDIT" "user.create"
assert_contains "审计含 member.update" "$AUDIT" "member.update"
assert_contains "审计含 workspace.bind" "$AUDIT" "workspace.bind"

# ---- owner 转移与仅剩 owner 删除（issue #157：转移未实现 + 删除死锁）----
U4=$(seed_user "$TOKEN" "m1-newowner-$TS" "Pass12345!")
WS2=$(seed_ws "$TOKEN" "m1-ws2-$TS" "$U1")
assert_eq "转移前 ownerUserId=U1" "$(req GET /admin/workspaces/$WS2 "" "$TOKEN" | jqr "['data']['ownerUserId']")" "$U1"
req PATCH /admin/workspaces/$WS2 "{\"ownerUserId\":$U4}" "" "$TOKEN" >/dev/null
assert_eq "owner 转移生效（#157）" "$(req GET /admin/workspaces/$WS2 "" "$TOKEN" | jqr "['data']['ownerUserId']")" "$U4"
assert_eq "原 owner 降为 member（可移除）" "$(req GET /admin/workspaces/$WS2/members "" "$TOKEN" | python3 -c "import sys,json;print([m['role'] for m in json.load(sys.stdin)['data'] if m['userId']==$U1][0])")" "member"
assert_eq "新 owner 成员角色 owner" "$(req GET /admin/workspaces/$WS2/members "" "$TOKEN" | python3 -c "import sys,json;print([m['role'] for m in json.load(sys.stdin)['data'] if m['userId']==$U4][0])")" "owner"
assert_eq "幽灵 owner 400" "$(code PATCH /admin/workspaces/$WS2 '{"ownerUserId":999999}' "" "$TOKEN")" "400"
# 成员 >1（owner + 原 owner）删除仍 409；移除降级成员后仅剩 owner → 200
assert_eq "多成员删除 409" "$(code DELETE /admin/workspaces/$WS2 "" "$TOKEN")" "409"
assert_eq "降级后的原 owner 可移除" "$(code DELETE /admin/workspaces/$WS2/members/$U1 "" "$TOKEN")" "200"
assert_eq "仅剩 owner 可删除（#157 死锁解除）" "$(code DELETE /admin/workspaces/$WS2 "" "$TOKEN")" "200"
# 直删路径：带 owner 但从未加过成员的空间直接删（级联清 owner 成员行）
WS3=$(seed_ws "$TOKEN" "m1-ws3-$TS" "$U4")
assert_eq "仅剩 owner 空间直删 200" "$(code DELETE /admin/workspaces/$WS3 "" "$TOKEN")" "200"
# 超长空间名 400（#149，原 500 Data too long）
assert_eq "200 字符空间名 400" "$(code POST /admin/workspaces "{\"name\":\"$(printf 'w%.0s' $(seq 200))\"}" "" "$TOKEN")" "400"

finish "M1-governance"

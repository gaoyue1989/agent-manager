#!/usr/bin/env bash
# 控制台拆分 e2e 冒烟（console-user-workspace-split e2e 落位表：M1 governance + M8 workspace-assets
# 的 API 链路首版非必需 job 脚本；M3/M4 主链路与 M5/M6 场景在核心 job / 独立脚本扩展，另行补充）。
#
# 前置：platform-backend 已部署（JWT_SECRET + ADMIN_INIT_* 已配置），frontend :30881 或直连
# backend :30880 可达；jq 可用。
# 用法：
#   BASE=http://<host>:30880/api/v1 ./console-split-e2e.sh
# 断言链路：
#   admin 登录 → 建用户 → 建工作空间 → 邀请成员 → bind 存量服务（default→业务空间，跨空间 409）
#   → 用户登录（非 admin 不可见 default 空间/不可进 admin 面）→ API Key 签发（明文一次/作用域）
#   → 吊销 → introspect 三态（M9 数据面语义，enforce 档生效）
set -euo pipefail

BASE="${BASE:-http://localhost:30880/api/v1}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-OafConsole2026}"
# 环境可选 AUTH_TOKEN（配置了才断言 legacy 401；未配置时 legacy 匿名 = 现状行为）
AUTH_TOKEN="${AUTH_TOKEN:-}"
PASS_CNT=0; FAIL_CNT=0

say()  { printf '%s\n' "$*"; }
ok()   { PASS_CNT=$((PASS_CNT+1)); say "  ✅ $*"; }
bad()  { FAIL_CNT=$((FAIL_CNT+1)); say "  ❌ $*"; }
assert_eq() { # assert_eq <desc> <got> <want>
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (got=$2 want=$3)"; fi
}

req() { # req <method> <path> [body] [""] [token]（body 后的 "" 占位与 token 两形态都兼容）
  local m="$1" p="$2" b="${3:-}" t="${4:-}"
  if [ -z "$t" ] && [ -n "${5:-}" ]; then t="$5"; fi
  if [ -n "$t" ] && [ -n "$b" ]; then
    curl -s -X "$m" "$BASE$p" -H 'Content-Type: application/json' -H "Authorization: Bearer $t" -d "$b"
  elif [ -n "$t" ]; then
    curl -s -X "$m" "$BASE$p" -H "Authorization: Bearer $t"
  elif [ -n "$b" ]; then
    curl -s -X "$m" "$BASE$p" -H 'Content-Type: application/json' -d "$b"
  else
    curl -s -X "$m" "$BASE$p"
  fi
}
code() { # code 同 req 兼容形态，只取状态码
  local m="$1" p="$2" b="${3:-}" t="${4:-}"
  if [ -z "$t" ] && [ -n "${5:-}" ]; then t="$5"; fi
  if [ -n "$t" ] && [ -n "$b" ]; then
    curl -s -o /dev/null -w '%{http_code}' -X "$m" "$BASE$p" -H 'Content-Type: application/json' -H "Authorization: Bearer $t" -d "$b"
  elif [ -n "$t" ]; then
    curl -s -o /dev/null -w '%{http_code}' -X "$m" "$BASE$p" -H "Authorization: Bearer $t"
  elif [ -n "$b" ]; then
    curl -s -o /dev/null -w '%{http_code}' -X "$m" "$BASE$p" -H 'Content-Type: application/json' -d "$b"
  else
    curl -s -o /dev/null -w '%{http_code}' -X "$m" "$BASE$p"
  fi
}
jqr() { python3 -c "import sys,json;d=json.load(sys.stdin);print(eval('d'+sys.argv[1]))" "$1" 2>/dev/null; }

say "== 控制台拆分 e2e 冒烟（BASE=$BASE）=="

# ---- M0：admin 登录 ----
say "-- M0 登录与路由分层"
LOGIN=$(req POST /auth/login "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}")
TOKEN=$(echo "$LOGIN" | jqr "['data']['token']" || true)
assert_eq "admin 登录拿到 token" "$([ -n "$TOKEN" ] && echo yes || echo no)" "yes"
assert_eq "错误密码 401" "$(code POST /auth/login '{"username":"admin","password":"wrong"}')" "401"
assert_eq "me 需 JWT（匿名 401）" "$(code GET /auth/me)" "401"
if [ -n "$AUTH_TOKEN" ]; then
  assert_eq "存量 services 仍要求 AUTH_TOKEN（未带凭据 401）" "$(code GET /services)" "401"
else
  say "  ⚠️ 环境未配 AUTH_TOKEN，跳过 legacy 401 断言（匿名 = 现状行为）"
fi

# ---- M1：治理链路 ----
say "-- M1 工作空间/用户/纳管"
TS=$(date +%s)
U_RESP=$(req POST /admin/users "{\"username\":\"e2e-user-$TS\",\"password\":\"E2ePass123!\",\"displayName\":\"E2E 用户\",\"role\":\"user\"}" "" "$TOKEN")
NEW_USER_ID=$(echo "$U_RESP" | jqr "['data']['id']" || true)
[ -n "${DEBUG:-}" ] && say "DBG U_RESP=$U_RESP NEW_USER_ID=$NEW_USER_ID"
assert_eq "admin 建号 201" "$([ -n "$NEW_USER_ID" ] && echo yes || echo no)" "yes"
WS_RESP=$(req POST /admin/workspaces "{\"name\":\"e2e-ws-$TS\",\"ownerUserId\":$NEW_USER_ID}" "" "$TOKEN")
WS_ID=$(echo "$WS_RESP" | jqr "['data']['id']" || true)
WS_SLUG=$(echo "$WS_RESP" | jqr "['data']['slug']" || true)
[ -n "${DEBUG:-}" ] && say "DBG WS_RESP=${WS_RESP:0:200} WS_ID=$WS_ID"
assert_eq "建空间（slug=ws-*）" "$(echo "$WS_SLUG" | grep -c '^ws-')" "1"
assert_eq "重复用户名 400" "$(code POST /admin/users "{\"username\":\"e2e-user-$TS\",\"password\":\"x\"}" "" "$TOKEN")" "400"
assert_eq "非空空间删除 409" "$(code DELETE /admin/workspaces/$WS_ID "" "" "$TOKEN")" "409"

# 找一个 default 空间的存量服务做纳管（无则跳过 bind 分支）
SVC_ID=$(req GET "/admin/workspaces/$(req GET /admin/workspaces "" "" "$TOKEN" | jqr "['data'][0]['id']")/agents" "" "$TOKEN" | jqr "['data'][0]['id']" 2>/dev/null || true)
DEF_WS=$(req GET /admin/workspaces "" "" "$TOKEN" | python3 -c "import sys,json;print(next((w['id'] for w in json.load(sys.stdin)['data'] if w['slug']=='default'),''))")
if [ -n "$SVC_ID" ] && [ "$SVC_ID" != "None" ] && [ -n "$DEF_WS" ]; then
  assert_eq "纳管 default→业务空间 200" "$(code POST /admin/workspaces/$WS_ID/bind "{\"serviceId\":$SVC_ID}" "" "$TOKEN")" "200"
  # 同空间重复纳管 = 幂等 200（当前归属即本空间，允许重挂）
  assert_eq "同空间重复纳管幂等 200" "$(code POST /admin/workspaces/$WS_ID/bind "{\"serviceId\":$SVC_ID}" "" "$TOKEN")" "200"
  # 跨空间纳管 409：另建一个空间做目标（已挂 WS 的服务不允许被挂到别处）
  WS2=$(req POST /admin/workspaces "{\"name\":\"e2e-ws2-$TS\"}" "" "$TOKEN")
  WS2_ID=$(echo "$WS2" | jqr "['data']['id']" || true)
  assert_eq "跨空间纳管 409 already_bound" "$(code POST /admin/workspaces/$WS2_ID/bind "{\"serviceId\":$SVC_ID}" "" "$TOKEN")" "409"
  req POST /admin/workspaces/$DEF_WS/bind "{\"serviceId\":$SVC_ID}" "" "$TOKEN" >/dev/null # 还原
else
  say "  ⚠️ 无存量服务可纳管，跳过 bind 分支"
fi

# 成员矩阵（owner 保护：不可移除 → 409 conflict；文档写 400，REST 冲突语义取 409）
assert_eq "owner 移除受保护" "$(code DELETE /admin/workspaces/$WS_ID/members/$(echo "$WS_RESP" | jqr "['data']['ownerUserId']") "" "" "$TOKEN")" "409"

# ---- M0/M1：用户侧鉴权边界 ----
say "-- 用户侧边界"
ULOGIN=$(req POST /auth/login "{\"username\":\"e2e-user-$TS\",\"password\":\"E2ePass123!\"}")
UTOKEN=$(echo "$ULOGIN" | jqr "['data']['token']" || true)
assert_eq "普通用户登录" "$([ -n "$UTOKEN" ] && echo yes || echo no)" "yes"
assert_eq "普通用户进 admin 面 403" "$(code GET /admin/users "" "" "$UTOKEN")" "403"
assert_eq "普通用户 default 空间 404（决议 2）" "$(code GET /console/workspaces/$DEF_WS/summary "" "$UTOKEN")" "404"
assert_eq "普通用户本空间 summary 200" "$(code GET /console/workspaces/$WS_ID/summary "" "$UTOKEN")" "200"

# ---- M8：API Key + M9 introspect ----
say "-- M8 API Key / M9 introspect"
KEY=$(req POST /console/workspaces/$WS_ID/api-keys '{"name":"e2e-key","expiresInDays":1}' "$UTOKEN")
PLAIN=$(echo "$KEY" | jqr "['data']['plaintext']" || true)
assert_eq "签发返回一次性明文 ak-*" "$(echo "$PLAIN" | grep -c '^ak-')" "1"
KEYS=$(req GET /console/workspaces/$WS_ID/api-keys "" "$UTOKEN")
assert_eq "列表仅前缀（无明文）" "$(echo "$KEYS" | grep -c "$PLAIN")" "0"
assert_eq "吊销 200" "$(code DELETE /console/workspaces/$WS_ID/api-keys/$(echo "$KEY" | jqr "['data']['id']") "" "$UTOKEN")" "200"
# introspect：吊销 key → 403（数据面语义，router enforce 档即拒绝）
INT=$(code GET /auth/introspect "" "" "$PLAIN")
assert_eq "introspect 吊销 key 403" "$INT" "403"
# JWT 通道 200 + X-User-Id
INT_JWT=$(curl -s -D - -o /dev/null "$BASE/auth/introspect" -H "Authorization: Bearer $UTOKEN" | tr -d '\r')
assert_eq "introspect JWT 200" "$(echo "$INT_JWT" | head -1 | grep -c 200)" "1"
assert_eq "introspect 回写 X-User-Id" "$(echo "$INT_JWT" | grep -ci "^x-user-id: u-")" "1"

say ""
say "== 结果：pass=$PASS_CNT fail=$FAIL_CNT =="
[ "$FAIL_CNT" = "0" ]

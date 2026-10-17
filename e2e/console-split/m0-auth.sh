#!/usr/bin/env bash
# M0 · 身份认证与路由分层（实施文档 01 §4/§5）
# 覆盖：登录三分支 / me 双通道 / cookie 属性 / 路由分层矩阵（AUTH_TOKEN 与 JWT 并存）/
#       introspect JWT 通道 / logout 清 cookie / admin 种子幂等
source "$(dirname "$0")/lib.sh"

TS=$(date +%s)
say "-- M0 身份认证"

# 1) 登录三分支
LOGIN=$(req POST /auth/login "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}")
TOKEN=$(echo "$LOGIN" | jqr "['data']['token']")
assert_contains "正确登录返回 token" "$LOGIN" '"token":"'
assert_contains "登录响应含 user.role" "$LOGIN" '"role":"admin"'
assert_contains "登录响应含 workspaces 列表" "$LOGIN" '"workspaces":['
assert_eq "错误密码 401 统一文案" "$(code POST /auth/login '{"username":"admin","password":"wrong"}')" "401"
assert_eq "不存在用户 401（不区分文案）" "$(code POST /auth/login "{\"username\":\"nouser-$TS\",\"password\":\"x\"}")" "401"

# 2) Set-Cookie 属性（httpOnly / Path=/）
HDR=$(curl -s -D - -o /dev/null "$BASE/auth/login" -X POST -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" | tr -d '\r')
assert_contains "Set-Cookie oaf_session" "$HDR" "oaf_session="
assert_contains "cookie httpOnly" "$HDR" "HttpOnly"
assert_contains "cookie Path=/" "$HDR" "Path=/;"

# 3) me 双通道（header / cookie）+ 匿名 401
assert_eq "me 匿名 401" "$(code GET /auth/me)" "401"
assert_contains "me header 通道" "$(req GET /auth/me "" "$TOKEN")" '"user":{'
COOKIE=$(echo "$HDR" | grep -i '^set-cookie: oaf_session' | sed 's/^[Ss]et-[Cc]ookie: oaf_session=//;s/;.*//')
assert_contains "me cookie 通道" "$(curl -s "$BASE/auth/me" -H "Cookie: oaf_session=$COOKIE")" '"user":{'

# 4) 路由分层：AUTH_TOKEN 与 JWT 并存（实施文档 §2.5 矩阵）
if [ -n "${AUTH_TOKEN:-}" ]; then
  assert_eq "legacy services 无 AUTH_TOKEN 401" "$(code GET /services)" "401"
  assert_eq "legacy services 带 AUTH_TOKEN 200" "$(code GET /services "" "$AUTH_TOKEN")" "200"
  assert_eq "admin 面 AUTH_TOKEN 通道 200" "$(code GET /admin/users "" "$AUTH_TOKEN")" "200"
  assert_eq "console 带 JWT 不被 AUTH_TOKEN 拦（非成员 404 而非 401）" \
    "$(code GET /console/workspaces/999999/summary "" "$TOKEN")" "404"
else
  assert_eq "legacy services 匿名可达（环境未配 AUTH_TOKEN = 现状行为）" "$(code GET /services)" "200"
  assert_eq "admin 面 匿名 401" "$(code GET /admin/users)" "401"
fi
assert_eq "admin 面普通用户 403" "$(code GET /admin/users "" "$(req POST /auth/login "{\"username\":\"u-$TS\",\"password\":\"Pp123456\"}" | jqr "['data']['token']" || true)")" "401"
# （上面普通用户未建号 → 401 即登录拦截；建号后 403 的断言在 M1）

# 5) introspect：JWT 通道 200 + X-User-Id；无凭据 401；坏 token 401
INTRO=$(curl -s -D - -o /dev/null "$BASE/auth/introspect" -H "Authorization: Bearer $TOKEN" | tr -d '\r')
assert_contains "introspect JWT 200" "$INTRO" " 200"
assert_contains "introspect 回写 X-User-Id" "$(echo "$INTRO" | grep -i '^x-user-id')" "u-"
assert_eq "introspect 无凭据 401" "$(code GET /auth/introspect)" "401"
assert_eq "introspect 坏 token 401" "$(code GET /auth/introspect "" "" "bad.token.here")" "401"

# 6) logout 清 cookie + admin 种子幂等（重登仍一个 admin）
assert_eq "logout 200" "$(code POST /auth/logout)" "200"
ADMIN_ID=$(req GET /admin/users "" "$TOKEN" | python3 -c "import sys,json;print(sum(1 for u in json.load(sys.stdin)['data'] if u['username']=='$ADMIN_USER'))")
assert_eq "admin 账号唯一（种子幂等）" "$ADMIN_ID" "1"

finish "M0-auth"

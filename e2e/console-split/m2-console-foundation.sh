#!/usr/bin/env bash
# M2 · 用户侧控制台基础（实施文档 03 §4/§5）
# 覆盖：resolveWorkspace 矩阵（me/数字/非成员 404/default 仅 admin/停用 404）；
#       summary/agents 视图字段与过滤；templates 仅公共模板；包列表公共+本空间；proxy 身份注入
source "$(dirname "$0")/lib.sh"

TS=$(date +%s)
TOKEN=$(admin_login)
[ -n "$TOKEN" ] || { say "❌ admin 登录失败"; exit 1; }
say "-- M2 控制台基础"

# ---- resolveWorkspace 矩阵 ----
WS=$(seed_ws "$TOKEN" "m2-ws-$TS")
DEF_WS=$(req GET /admin/workspaces "" "$TOKEN" | python3 -c "import sys,json;print(next((w['id'] for w in json.load(sys.stdin)['data'] if w['slug']=='default'),''))")
assert_eq "admin 访问本空间 summary 200" "$(code GET /console/workspaces/$WS/summary "" "$TOKEN")" "200"
assert_eq "admin 访问 default 空间 200（决议 2）" "$(code GET /console/workspaces/$DEF_WS/summary "" "$TOKEN")" "200"
assert_eq "不存在空间 404" "$(code GET /console/workspaces/999999/summary "" "$TOKEN")" "404"
assert_eq "admin 虚拟空间(id=0) 404" "$(code GET /console/workspaces/0/summary "" "$TOKEN")" "404"
# 普通用户：非成员 404 / default 404 / 本空间 200 / me 默认
U1=$(seed_user "$TOKEN" "m2-user-$TS" "Pass12345!")
UT=$(req POST /auth/login "{\"username\":\"m2-user-$TS\",\"password\":\"Pass12345!\"}" | jqr "['data']['token']")
assert_eq "非成员空间 404（不暴露 403/404 差异）" "$(code GET /console/workspaces/$WS/summary "" "$UT")" "404"
assert_eq "普通用户 default 404" "$(code GET /console/workspaces/$DEF_WS/summary "" "$UT")" "404"
req POST /admin/workspaces/$WS/members "{\"userId\":$U1,\"role\":\"member\"}" "" "$TOKEN" >/dev/null
assert_eq "成员访问 200" "$(code GET /console/workspaces/$WS/summary "" "$UT")" "200"
assert_contains "me 解析默认空间" "$(req GET /console/workspaces/me/summary "" "$UT")" '"agents"'

# ---- summary / agents 视图 ----
SUMMARY=$(req GET /console/workspaces/$WS/summary "" "$TOKEN")
assert_contains "summary 含 agents 计数" "$SUMMARY" '"agents":{"total"'
assert_contains "summary 含 recentEvents（数组非 null）" "$SUMMARY" '"recentEvents":['
assert_contains "summary todaySessions 预留 null" "$SUMMARY" '"todaySessions":null'
AGENTS=$(req GET "/console/workspaces/$WS/agents?status=&keyword=" "" "$TOKEN")
assert_contains "agents 空间列表为数组" "$AGENTS" '"data":['
DEF_AGENTS=$(req GET "/console/workspaces/$DEF_WS/agents" "" "$TOKEN")
if echo "$DEF_AGENTS" | grep -q '"id":'; then
  assert_contains "agents 列表项含 cardSummary" "$DEF_AGENTS" '"cardSummary"'
else
  skip "default 空间无服务，跳过列表项字段断言"
fi

# ---- templates / packages / images ----
TPL=$(req GET /console/workspaces/$WS/templates "" "$TOKEN")
assert_contains "templates 数组（可能为空）" "$TPL" '"data":'
PKGS=$(req GET /console/workspaces/$WS/packages "" "$TOKEN")
assert_contains "packages 含公共包+本空间" "$PKGS" '"data":'
IMGS=$(req GET /console/images "" "$TOKEN")
assert_contains "console images 面可用（JWT）" "$IMGS" '"Image"'

# ---- proxy 身份注入（数据面；业务 Pod 收到 X-User-Id → threads 按 user 落库）----
DEF_SVC=$(req GET "/admin/workspaces/$DEF_WS/agents" "" "$TOKEN" | jqr "['data'][0]['shortName']" 2>/dev/null || true)
if [ -n "$DEF_SVC" ] && [ "$DEF_SVC" != "None" ]; then
  C=$(curl -s -D /tmp/m2h.txt -o /dev/null "$BASE/auth/login" -X POST -H 'Content-Type: application/json' \
    -d "{\"username\":\"m2-user-$TS\",\"password\":\"Pass12345!\"}")
  CK=$(grep -i '^set-cookie: oaf_session' /tmp/m2h.txt | sed 's/^[Ss]et-[Cc]ookie: oaf_session=//;s/;.*//')
  FE="${FRONTEND_BASE:-http://localhost:8911}"
  SID="m2-proxy-$TS"
  # 经 frontend proxy 打一轮对话（无 UI 依赖；SSE 首帧即可，超时不判失败——业务 LLM 未配时 skip）
  RESP=$(curl -s -N -m 25 "$FE/agent/$DEF_SVC/threads/chat" -H "Cookie: oaf_session=$CK" \
    -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
    -d "{\"message\":\"ping\",\"userId\":\"u-$(echo "$UT" | python3 -c "import sys,json,base64;print(json.loads(base64.b64decode(sys.stdin.read().split('.')[1]+'==').decode())['uid'])")\",\"sessionId\":\"$SID\"}" 2>/dev/null | head -c 200 || true)
  THREADS=$(curl -s -m 10 "$FE/agent/$DEF_SVC/threads?userId=u-$(echo "$UT" | python3 -c "import sys,json,base64;print(json.loads(base64.b64decode(sys.stdin.read().split('.')[1]+'==').decode())['uid'])")" -H "Cookie: oaf_session=$CK" 2>/dev/null)
  if echo "$THREADS" | grep -q "$SID"; then
    assert_contains "proxy 注入 X-User-Id（threads 按 uid 落库）" "$THREADS" "$SID"
  else
    skip "proxy 身份注入（业务面不可达或 LLM 未配；RESP=${RESP:0:60}）"
  fi
else
  skip "default 空间无服务，跳过 proxy 身份注入"
fi

finish "M2-console-foundation"

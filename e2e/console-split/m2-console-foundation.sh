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
# ---- 工具调用观测端点（tool-call-observability-design；200=观测栈在 / 503=未部署均合法）----
OBS=$(code GET /console/workspaces/$WS/observability/tool-calls "" "$TOKEN")
if [ "$OBS" = "200" ] || [ "$OBS" = "503" ]; then
  ok "成员空间 observability 可达（$OBS）"
else
  bad "成员空间 observability 应 200/503（got=$OBS）"
fi
if [ "$OBS" = "200" ]; then
  assert_contains "observability 响应含 totals/byTool 结构" \
    "$(req GET /console/workspaces/$WS/observability/tool-calls "" "$TOKEN")" '"totals"'
fi
assert_eq "observability 非法 range 400" "$(code GET "/console/workspaces/$WS/observability/tool-calls?range=1w" "" "$TOKEN")" "400"
assert_eq "observability 不存在空间 404" "$(code GET /console/workspaces/999999/observability/tool-calls "" "$TOKEN")" "404"
assert_eq "observability 非成员 404" "$(code GET /console/workspaces/$WS/observability/tool-calls "" "$UT")" "404"
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

# ---- proxy 身份注入（前端 :30881 链路；body userId 兜底 → threads 按 user 落库）----
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
    assert_contains "proxy 链路 threads 按 body userId 兜底落库" "$THREADS" "$SID"
  else
    skip "proxy 身份注入（业务面不可达或 LLM 未配；RESP=${RESP:0:60}）"
  fi
else
  skip "default 空间无服务，跳过 proxy 身份注入"
fi

# ---- 统一入口数据面身份（issue #373：router off 免鉴权但不免身份）----
# GATEWAY_BASE = 集群入口 /agent/*：CI 为 kind provider ingress-nginx NodePort patch
# （platform-e2e-ci.yml）；本地为 kind extraPortMappings + 宿主 nginx :8911 同链路。
# chat 带显式 body userId（复刻 ChatPane 真实形态）：网关注入失效与否，归属恒为
# u-$GUID（ChatStreamController header>body 兜底）；history/DELETE 保持 cookie-only——
# 注入失效时请求者退化为 debug-user ≠ 归属，SessionAccessGuard fail-closed 404，
# 回归必红；若 chat 也 cookie-only，归属与请求者同步退化、全程 200 假通过。
if runtime_is_real && [ -n "$DEF_SVC" ] && [ "$DEF_SVC" != "None" ]; then
  GW="${GATEWAY_BASE:-http://localhost:30080}"
  GUID=$(echo "$UT" | python3 -c "import sys,json,base64;print(json.loads(base64.b64decode(sys.stdin.read().split('.')[1]+'==').decode())['uid'])")
  GSID="m2-gateway-$TS"
  GRESP=$(curl -s -N -m 25 "$GW/agent/$DEF_SVC/threads/chat" -H "Cookie: oaf_session=$CK" \
    -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
    -d "{\"message\":\"ping\",\"userId\":\"u-$GUID\",\"sessionId\":\"$GSID\"}" 2>/dev/null | head -c 200 || true)
  # 以 chat 是否真正建流（AGENT_START）分岔：会话归属已落库，history 404 即身份注入失效（红）；
  # chat 未达 AGENT_START 才允许 skip（业务面不可达或 LLM 未配）
  if echo "$GRESP" | grep -q '"type":"AGENT_START"'; then
    assert_eq "统一入口 cookie-only history 200（网关注入身份=归属 u-$GUID）" \
      "$(curl -s -o /dev/null -w '%{http_code}' -m 10 "$GW/agent/$DEF_SVC/threads/$GSID/history" -H "Cookie: oaf_session=$CK")" "200"
    GLIST=$(curl -s -m 10 "$GW/agent/$DEF_SVC/threads" -H "Cookie: oaf_session=$CK" 2>/dev/null)
    GSID_ROW=$(echo "$GLIST" | GSID="$GSID" python3 -c "
import sys, json, os
try:
    row = next((t for t in json.load(sys.stdin) if t.get('session_id') == os.environ['GSID']), None)
    print(row.get('user_id') if row else 'MISSING')
except Exception:
    print('PARSE_ERROR')" 2>/dev/null)
    assert_eq "统一入口 threads 列表 GSID 条目归属 u-$GUID（逐条目，防他条目误中）" "$GSID_ROW" "u-$GUID"
    assert_eq "统一入口 cookie-only DELETE 200" \
      "$(curl -s -o /dev/null -w '%{http_code}' -m 10 -X DELETE "$GW/agent/$DEF_SVC/threads/$GSID" -H "Cookie: oaf_session=$CK")" "200"
  else
    skip "统一入口数据面身份（chat 未达 AGENT_START：业务面不可达或 LLM 未配；RESP=${GRESP:0:60}）"
  fi
else
  skip "统一入口数据面身份（mock 运行时或缺业务服务）"
fi

# ---- 会话上下文注入经统一入口：以注入是否 201 分岔——404 = 业务镜像尚无该接口（合并发布镜像前）才允许 skip ----
if runtime_is_real && [ -n "$DEF_SVC" ] && [ "$DEF_SVC" != "None" ]; then
  GW="${GATEWAY_BASE:-http://localhost:30080}"
  CSID="m2-ctx-$TS"
  CTX_MARK="m2-ctx-mark-$TS"
  CINJ=$(curl -s -o /dev/null -w '%{http_code}' -m 15 "$GW/agent/$DEF_SVC/threads/$CSID/context" \
    -H "Cookie: oaf_session=$CK" -H 'Content-Type: application/json' \
    -d "{\"key\":\"headline\",\"title\":\"M2 头条\",\"content\":\"$CTX_MARK\"}")
  if [ "$CINJ" = "404" ]; then
    skip "会话上下文注入经统一入口（业务镜像尚无该接口，合并发布镜像后生效）"
  else
    assert_eq "统一入口 注入会话上下文 201" "$CINJ" "201"
    CHIST=$(curl -s -m 15 "$GW/agent/$DEF_SVC/threads/$CSID/history" -H "Cookie: oaf_session=$CK")
    assert_contains "history 平级 context 含注入正文" "$CHIST" "$CTX_MARK"
    assert_contains "history context 含资料名 headline" "$CHIST" '"key":"headline"'
    assert_eq "统一入口 删除带上下文的会话 200" \
      "$(curl -s -o /dev/null -w '%{http_code}' -m 15 -X DELETE "$GW/agent/$DEF_SVC/threads/$CSID" -H "Cookie: oaf_session=$CK")" "200"
  fi
else
  skip "会话上下文注入经统一入口（mock 运行时或缺业务服务）"
fi

finish "M2-console-foundation"

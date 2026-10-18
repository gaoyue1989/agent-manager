#!/usr/bin/env bash
# 控制台拆分 e2e 公共库（按功能套件共用）。
# 断言工具：req/code（curl 四分支显式形态）+ ok/bad/assert_eq/assert_contains/assert_not_contains。
# 环境变量：BASE / ADMIN_USER / ADMIN_PASS / AUTH_TOKEN（可选）/ KUBE_OK（kubectl 可用时置 1）。
set -u

BASE="${BASE:-http://localhost:30080/api/v1}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-OafConsole2026}"
PASS_CNT=0; FAIL_CNT=0; SKIP_CNT=0

say()  { printf '%s\n' "$*"; }
ok()   { PASS_CNT=$((PASS_CNT+1)); say "  ✅ $*"; }
bad()  { FAIL_CNT=$((FAIL_CNT+1)); say "  ❌ $*"; }
skip() { SKIP_CNT=$((SKIP_CNT+1)); say "  ⏭️  $*"; }

assert_eq() { # assert_eq <desc> <got> <want>
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (got=$2 want=$3)"; fi
}
assert_contains() { # assert_contains <desc> <haystack> <needle>
  case "$2" in *"$3"*) ok "$1";; *) bad "$1 (missing: $3)";; esac
}
assert_not_contains() {
  case "$2" in *"$3"*) bad "$1 (unexpected: $3)";; *) ok "$1";; esac
}
finish() {
  say ""; say "== [$1] pass=$PASS_CNT fail=$FAIL_CNT skip=$SKIP_CNT =="
  [ "$FAIL_CNT" = "0" ]
}

req() { # req <method> <path> [body] [""] [token]
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
code() { # code 同 req 形态，只取状态码
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
jqr() { # 从首个 '{' 起解析（容忍偶发响应体前缀噪声——单节点 kind 高负载下 ingress 偶发拼接双文档）
  python3 -c "
import sys,json
raw=sys.stdin.read()
d=json.loads(raw[raw.index('{'):])
print(eval('d'+sys.argv[1]))" "$1" 2>/dev/null; }

# admin_login -> echo token（失败输出空）
admin_login() {
  req POST /auth/login "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" | jqr "['data']['token']"
}
# seed_user <token> <username> <password> [role] -> id
seed_user() {
  req POST /admin/users "{\"username\":\"$2\",\"password\":\"$3\",\"role\":\"${4:-user}\"}" "" "$1" | jqr "['data']['id']"
}
# seed_ws <token> <name> [ownerUserId] -> id
seed_ws() {
  req POST /admin/workspaces "{\"name\":\"$2\"${3:+,\"ownerUserId\":$3}}" "" "$1" | jqr "['data']['id']"
}
# wait_status <token> <ws> <svcId> <timeoutSec> <want...> -> 终态（timeout 紧跟 id，防错位）
wait_status() {
  local t="$1" ws="$2" id="$3" timeout="$4"; shift 4; local wants=("$@")
  local deadline=$((SECONDS + timeout)) st=""
  while [ $SECONDS -lt $deadline ]; do
    st=$(req GET "/console/workspaces/$ws/agents/$id" "" "$t" | jqr "['data']['status']" 2>/dev/null || echo "")
    for w in "${wants[@]}"; do [ "$st" = "$w" ] && { echo "$st"; return 0; }; done
    sleep 5
  done
  echo "$st"
}
# 集群工具可用性（pod env / secret 断言用；不可用则相关用例 skip）
if command -v kubectl >/dev/null 2>&1 && kubectl -n agent-platform get deploy oaf-platform-backend >/dev/null 2>&1; then
  KUBE_OK=1; KNS=agent-platform
else
  KUBE_OK=0; KNS=""
fi

# ensure_verified <token> <ws> <connId>：连通性测试重试至多 3 次（LLM 供应商偶发 5xx/限流）
ensure_verified() {
  local t="$1" ws="$2" id="$3" out="" wait
  for wait in 5 10 20 40 60; do
    out=$(req POST /console/workspaces/$ws/model-connections/$id/test "" "$t")
    echo "$out" | grep -q '"ok":true' && { echo "$out"; return 0; }
    sleep $wait
  done
  echo "$out"
}

#!/usr/bin/env bash
# issue #373：ROUTER_AUTH_MODE 三态行为级矩阵验证（nginx 真容器 + 本地 mock upstream）。
# 从 manifests/platform-router.yaml 与 helm template 渲染物（charts 分叉防回归）分别提取
# 真实 ConfigMap 模板与三档拦截片段，sed 替换 introspect/业务 upstream 指向本地 mock 后，
# 起 nginx 容器逐案断言。行为口径（nginx -t 全过的错误落法仍可能行为错误，本脚本才是结论）：
#   off     恒放行（无凭据/坏凭据/introspect 不可用）——必须断言业务响应体到达（body 级 marker，
#           「200 但业务未被调用」的假放行由此防回归）+ 身份正确（无结果回落自报/有结果覆盖自报）
#   report  坏凭据放行（不信任自报）+ introspect 不可用拒绝（5xx fail-closed）
#   enforce 无凭据/坏凭据 401、introspect 不可用 500（全 fail-closed）、好凭据 200 + 注入身份
# 依赖：docker、python3、curl；helm 可选（缺则跳过 charts 复跑）。缺 docker 时 exit 2（SKIP）。
set -u

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
NGINX_IMAGE="${NGINX_IMAGE:-nginx:1.27.1-alpine}"
PORT_NGINX=18085        # 被测 router（--network host 直听宿主）
PORT_INTROSPECT=18086   # introspect mock（JWT/cookie 判定）
PORT_BIZ=18087          # 业务 mock（回显收到的 X-User-Id → body marker）
PORT_DEAD=18088         # 故意不监听：connect refused → nginx 502（backend 不可用模拟）
PASS=0; FAIL=0; MOCK_PID=""

say() { printf '%s\n' "$*"; }
expect() { # expect <desc> <got> <want>
  if [ "$2" = "$3" ]; then PASS=$((PASS+1)); say "  ✅ $1"; else FAIL=$((FAIL+1)); say "  ❌ $1 (got=$2 want=$3)"; fi
}

# check_case <desc> <want_status> <want_body_regex|-> <curl args...>
#   want_body_regex 为 '-' 时仅断言状态码 + body 不含业务 marker（拒绝案，防假放行反例）
check_case() {
  local desc="$1" want_st="$2" want_re="$3"; shift 3
  local st body
  st=$(curl -s -o /tmp/router-verify-body -w '%{http_code}' "$@" 2>/dev/null)
  body=$(cat /tmp/router-verify-body 2>/dev/null)
  if [ "$st" != "$want_st" ]; then
    FAIL=$((FAIL+1)); say "  ❌ $desc (status=$st want=$want_st body=${body:0:80})"
    return
  fi
  if [ "$want_re" = "-" ]; then
    if echo "$body" | grep -q 'called uid='; then
      FAIL=$((FAIL+1)); say "  ❌ $desc (拒绝案但业务被调用: ${body:0:80})"
    else
      PASS=$((PASS+1)); say "  ✅ $desc (status=$st 业务未达)"
    fi
  elif echo "$body" | grep -qE "$want_re"; then
    PASS=$((PASS+1)); say "  ✅ $desc (status=$st body=${body:0:60})"
  else
    FAIL=$((FAIL+1)); say "  ❌ $desc (status=$st body=${body:0:80} want_body_re=$want_re)"
  fi
}

cleanup() {
  [ -n "$MOCK_PID" ] && kill "$MOCK_PID" 2>/dev/null
  docker rm -f router-auth-verify >/dev/null 2>&1
  rm -rf "${WORK:-}" /tmp/router-verify-body
}
trap cleanup EXIT

WORK=$(mktemp -d)

# ---- ConfigMap 提取器：data 块下 "  key: |" 的 4 空格缩进值（不依赖 PyYAML）----
cat > "$WORK/extract.py" <<'PYEOF'
import sys, re
lines = open(sys.argv[1]).read().splitlines()
i = 0
while i < len(lines) and lines[i].strip() != 'data:':
    i += 1
if i == len(lines):
    sys.exit(f'no data block in {sys.argv[1]}')
i += 1
out = {}
while i < len(lines):
    m = re.match(r'^  ([A-Za-z0-9_.\-]+): \|$', lines[i])
    if not m:
        if lines[i] and not lines[i].startswith(' '):
            break
        i += 1
        continue
    key, i, val = m.group(1), i + 1, []
    while i < len(lines) and (lines[i].startswith('    ') or lines[i].strip() == ''):
        val.append(lines[i][4:] if lines[i].startswith('    ') else '')
        i += 1
    out[key] = '\n'.join(val)
outdir = sys.argv[2]
for key in sys.argv[3:]:
    if key not in out:
        sys.exit(f'key {key} not found in {sys.argv[1]}')
    open(f'{outdir}/{key}', 'w').write(out[key] + '\n')
PYEOF

# ---- mock upstream：introspect（Bearer ok-jwt / cookie ok-cookie → u-42；disabled → 403；
#      其余 401）+ 业务回显（body marker = 收到的 X-User-Id；18088 恒 connect refused → 502）----
cat > "$WORK/mock.py" <<'PYEOF'
import http.server, json, re, threading, sys

class Introspect(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.0'
    def log_message(self, *a): pass
    def reply(self, code, uid=None):
        self.send_response(code)
        if uid:
            self.send_header('X-User-Id', uid)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        body = f'{{"userId":"{uid}"}}'.encode() if uid else b'{"code":%d,"message":"mock"}' % code
        self.wfile.write(body)
    def do_GET(self):
        auth = self.headers.get('Authorization') or ''
        cookie = self.headers.get('Cookie') or ''
        if auth.startswith('Bearer '):
            tok = auth[7:]
            if tok == 'ok-jwt':
                self.reply(200, 'u-42')
            elif tok == 'disabled-jwt':
                self.reply(403)
            elif tok == 'e404-jwt':   # 非 401/403/5xx 白名单码：auth-off error_page 漏列即回归
                self.reply(404)
            elif tok == 'e429-jwt':   # 限流码同上
                self.reply(429)
            else:
                self.reply(401)
            return
        if 'oaf_session=ok-cookie' in cookie:
            self.reply(200, 'u-42')
        else:
            self.reply(401)

class Biz(http.server.BaseHTTPRequestHandler):
    # 归属裁决最小模拟（复刻业务面 SessionAccessGuard 契约）：chat 记会话归属（收到的
    # X-User-Id，缺失=debug-user），history/DELETE 按 owner==请求者放行、未建会话 404——
    # 使「身份注入失效 → cookie-only history 404」成为矩阵内可门禁的断言
    protocol_version = 'HTTP/1.0'
    owners = {}
    def log_message(self, *a): pass
    def reply(self, code, uid):
        body = ('called uid=%s' % uid).encode() if code == 200 else b'{"error":"thread_not_found"}'
        self.send_response(code)
        self.send_header('Content-Type', 'text/plain')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def handle_one(self):
        raw = self.headers.get('X-User-Id')
        uid = raw or 'missing'          # 回显：missing = 头未到达业务面（区分注入失效与匿名自报）
        requester = raw or 'debug-user' # 裁决：复刻业务面匿名回落
        path = self.path.split('?')[0]
        n = int(self.headers.get('Content-Length') or 0)
        payload = self.rfile.read(n) if n else b''
        if path == '/threads/chat' and self.command == 'POST':
            sid = ''
            try:
                sid = json.loads(payload or b'{}').get('sessionId') or ''
            except Exception:
                pass
            if sid:
                self.owners[sid] = requester
            self.reply(200, uid)
            return
        m = re.match(r'^/threads/([^/]+)(/history)?$', path)
        if m:
            owner = self.owners.get(m.group(1))
            if owner is None or owner != requester:
                self.reply(404, uid)
                return
            self.reply(200, uid)
            return
        self.reply(200, uid)
    do_GET = handle_one
    do_POST = handle_one
    do_DELETE = handle_one

for port, h in ((int(sys.argv[1]), Introspect), (int(sys.argv[2]), Biz)):
    srv = http.server.ThreadingHTTPServer(('127.0.0.1', port), h)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
threading.Event().wait()  # 双端口常驻（顺序 serve_forever 会阻塞，第二端口起不来）
PYEOF

start_container() { # start_container <confdir> <authdir>
  docker rm -f router-auth-verify >/dev/null 2>&1
  local run_err
  run_err=$(docker run -d --name router-auth-verify --network host \
    -v "$1":/etc/nginx/conf.d:ro -v "$2":/etc/nginx/auth-mode:ro \
    "$NGINX_IMAGE" 2>&1) || { say "  ❌ nginx 容器启动失败: ${run_err:0:120}"; FAIL=$((FAIL+1)); return 1; }
  for _ in $(seq 1 40); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT_NGINX/healthz" 2>/dev/null)" = "200" ] && return 0
    sleep 0.3
  done
  say "  ❌ nginx 未就绪，日志: $(docker logs router-auth-verify 2>&1 | tail -3 | tr '\n' ' ' | cut -c1-200)"
  FAIL=$((FAIL+1)); return 1
}

# render <srcdir> <destdir> <mode> <introspect_port> <nginx_port>：envsubst 双变量 + upstream 替换
render() {
  local src="$1" dst="$2" mode="$3" ip="$4" np="$5"
  mkdir -p "$dst"
  sed -e "s|\${ROUTER_AUTH_MODE}|$mode|g" \
      -e "s|\${DNS_RESOLVER}|127.0.0.11|g" \
      -e "s|resolver 10\.96\.0\.10|resolver 127.0.0.11|g" \
      -e "s|resolver kube-dns\.kube-system\.svc\.cluster\.local|resolver 127.0.0.11|g" \
      -e "s|oaf-platform-backend\.[^ ]*\.svc\.cluster\.local:8080/api/v1/auth/introspect|127.0.0.1:$ip/api/v1/auth/introspect|g" \
      -e "s|oaf-\$short-svc\.[^ ]*\.svc\.cluster\.local:8100|127.0.0.1:$PORT_BIZ|g" \
      -e "s|listen 8080;|listen $np;|g" \
      "$src/default.conf.template" > "$dst/default.conf"
}

run_matrix() { # run_matrix <label> <extract-src-file>
  local label="$1" src="$2"
  say "== $label 三态行为矩阵 =="
  local conf="$WORK/$label-conf" auth="$WORK/$label-auth"
  mkdir -p "$conf" "$auth"
  python3 "$WORK/extract.py" "$src" "$conf" default.conf.template auth-off.conf auth-report.conf auth-enforce.conf || return 1
  cp "$conf"/auth-off.conf "$conf"/auth-report.conf "$conf"/auth-enforce.conf "$auth/"
  local url="http://127.0.0.1:$PORT_NGINX/agent/demo-svc/threads"

  render "$conf" "$WORK/run" off "$PORT_INTROSPECT" "$PORT_NGINX"
  start_container "$WORK/run" "$auth" || return 1
  check_case "off 无凭据放行（身份空，业务恒达）" 200 '^called uid=missing$' "$url"
  check_case "off 坏凭据放行回落自报 u-9" 200 '^called uid=u-9$' -H 'Authorization: Bearer bad-jwt' -H 'X-User-Id: u-9' "$url"
  check_case "off 好凭据鉴权结果覆盖恶意自报" 200 '^called uid=u-42$' -H 'Authorization: Bearer ok-jwt' -H 'X-User-Id: u-9' "$url"
  check_case "off 登录 cookie 通道注入身份（#373）" 200 '^called uid=u-42$' -H 'Cookie: oaf_session=ok-cookie' "$url"
  check_case "off introspect 404 异常码放行回落自报（error_page 漏列码回归门）" 200 '^called uid=u-9$' \
    -H 'Authorization: Bearer e404-jwt' -H 'X-User-Id: u-9' "$url"
  check_case "off introspect 429 限流码放行（error_page 漏列码回归门）" 200 '^called uid=missing$' \
    -H 'Authorization: Bearer e429-jwt' "$url"
  # 归属裁决契约（mock 复刻业务面 SessionAccessGuard；CI 可执行的 #373 归属覆盖）：
  # chat 落归属 + cookie-only history/DELETE 裁决，网关身份注入失效时此组必红
  check_case "off cookie-only chat 落归属 u-42（body 无 userId）" 200 '^called uid=u-42$' \
    -X POST -H 'Cookie: oaf_session=ok-cookie' -H 'Content-Type: application/json' \
    -d '{"message":"ping","sessionId":"mock-own-1"}' \
    "http://127.0.0.1:$PORT_NGINX/agent/demo-svc/threads/chat"
  check_case "off cookie-only history 归属裁决通过（注入失效此案必红）" 200 '^called uid=u-42$' \
    -H 'Cookie: oaf_session=ok-cookie' \
    "http://127.0.0.1:$PORT_NGINX/agent/demo-svc/threads/mock-own-1/history"
  check_case "off cookie-only DELETE 归属裁决通过" 200 '^called uid=u-42$' \
    -H 'Cookie: oaf_session=ok-cookie' -X DELETE \
    "http://127.0.0.1:$PORT_NGINX/agent/demo-svc/threads/mock-own-1"
  check_case "off cookie-only history 未建会话 404（fail-closed 对照）" 404 - \
    -H 'Cookie: oaf_session=ok-cookie' \
    "http://127.0.0.1:$PORT_NGINX/agent/demo-svc/threads/mock-none/history"
  render "$conf" "$WORK/run" off "$PORT_DEAD" "$PORT_NGINX"
  start_container "$WORK/run" "$auth" || return 1
  check_case "off introspect 不可用放行回落自报" 200 '^called uid=u-9$' -H 'X-User-Id: u-9' "$url"

  render "$conf" "$WORK/run" report "$PORT_INTROSPECT" "$PORT_NGINX"
  start_container "$WORK/run" "$auth" || return 1
  check_case "report 坏凭据放行但不信任自报" 200 '^called uid=missing$' -H 'Authorization: Bearer bad-jwt' -H 'X-User-Id: u-9' "$url"
  check_case "report cookie 好凭据注入身份" 200 '^called uid=u-42$' -H 'Cookie: oaf_session=ok-cookie' "$url"
  check_case "report introspect 404 异常码拒绝（fail-closed 对照）" 500 - -H 'Authorization: Bearer e404-jwt' "$url"
  render "$conf" "$WORK/run" report "$PORT_DEAD" "$PORT_NGINX"
  start_container "$WORK/run" "$auth" || return 1
  check_case "report introspect 不可用拒绝（fail-closed）" 500 - "$url"

  render "$conf" "$WORK/run" enforce "$PORT_INTROSPECT" "$PORT_NGINX"
  start_container "$WORK/run" "$auth" || return 1
  check_case "enforce 无凭据 401" 401 - "$url"
  check_case "enforce 坏凭据 401" 401 - -H 'Authorization: Bearer bad-jwt' "$url"
  check_case "enforce 禁用用户 403" 403 - -H 'Authorization: Bearer disabled-jwt' "$url"
  check_case "enforce 好凭据 200 + 注入身份" 200 '^called uid=u-42$' -H 'Authorization: Bearer ok-jwt' "$url"
  check_case "enforce introspect 404 异常码拒绝（fail-closed 对照）" 500 - -H 'Authorization: Bearer e404-jwt' "$url"
  render "$conf" "$WORK/run" enforce "$PORT_DEAD" "$PORT_NGINX"
  start_container "$WORK/run" "$auth" || return 1
  check_case "enforce introspect 不可用拒绝（fail-closed）" 500 - "$url"
}

if ! docker info >/dev/null 2>&1; then
  say "❌ docker 不可用，无法做行为级验证"; exit 2
fi
if ! docker image inspect "$NGINX_IMAGE" >/dev/null 2>&1; then
  say "⚠️ 本地无 $NGINX_IMAGE，尝试拉取…"
  docker pull "$NGINX_IMAGE" >/dev/null 2>&1 || { say "❌ 拉取失败"; exit 2; }
fi

python3 "$WORK/mock.py" "$PORT_INTROSPECT" "$PORT_BIZ" &
MOCK_PID=$!
sleep 0.5

run_matrix manifests "$ROOT/manifests/platform-router.yaml" || { say "❌ manifests 矩阵执行失败"; exit 1; }

# charts 渲染物复跑（防 manifests/charts 双份模板分叉）
if command -v helm >/dev/null 2>&1; then
  helm template router-verify "$ROOT/charts/oaf-platform" \
    --set backend.jwtSecret=verify --set persistence.existingClaim=platform-data \
    --set external.mysql.host=verify --set external.mysql.username=u --set external.mysql.password=p \
    --set platformDefaults.seed.enabled=false \
    --set 'images.business[0].image=verify/af:v1' --set 'images.business[0].label=af' \
    --show-only templates/router-configmap.yaml > "$WORK/helm-router.yaml" 2>/dev/null \
    && run_matrix charts "$WORK/helm-router.yaml" \
    || { say "  ⏭️ charts 渲染物复跑失败（helm values 不满足）"; FAIL=$((FAIL+1)); }
else
  say "  ⏭️ helm 不存在，跳过 charts 渲染物复跑"
fi

say ""
say "== router-auth-modes-verify pass=$PASS fail=$FAIL =="
[ "$FAIL" = "0" ]

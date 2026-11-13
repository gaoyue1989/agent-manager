#!/usr/bin/env bash
# M12 · 沙箱管理面（sandbox-pool-opensandbox-design §9.4）：
#   ① /settings 沙箱组模板断言（新 3 键 + SANDBOX_TIMEOUT_MINUTES placeholder 30）→ 配置落 platform_config；
#   ② admin 代理八端点：summary 聚合 / 创建池（池名-服务绑定契约 {服务k8s名}-pool + 预设模板+容量，mock 载荷断言）/
#      容量调整（PUT 仅 capacitySpec）/ 删除池 / 沙箱列表（state 过滤）/ 销毁；管理操作各产生一条审计记录；
#   ③ console 只读端点：metadata.service ∈ ws 服务集合过滤（空集合滤空 + mysql 造服务行正向断言）+ 无操作端点；
#   ④ 上游故障注入：mock 501 → 503（kubernetes runtime 文案）、401 → 502（指向 /settings）；
#   ⑤ 未配置 URL（platform_config 行直删）→ 503 + /settings 引导文案；console 端点同态断言
#      503 + 成员通用文案（不含 /settings 指引，issue #297）。
# mock 侧断言 / 故障注入 / platform_config 行级操作需 kubectl（KUBE_OK=0 时相应 skip；
# 上游用 mock：OPENSANDBOX_SERVER_URL 指向集群内 mock-sandbox svc，CI 由 workflow 预置）。
source "$(dirname "$0")/lib.sh"

TS=$(date +%s)
TOKEN=$(admin_login)
[ -n "$TOKEN" ] || { say "❌ admin 登录失败"; exit 1; }
say "-- M12 沙箱管理面"

# 前置门控（评审遗留，套件纪律「缺前置 exit 2 = SKIP」）：mock-sandbox 上游仅存在于
# CI overlay（manifests/ci/mock-sandbox.yaml）——本地集群无该 svc 时，所有上游载荷断言
# 与 admin CRUD（经 backend → mock）必然 FAIL 且 2>/dev/null 静默难排查；显式 SKIP 并提示
if [ "$KUBE_OK" = "1" ] && ! kubectl -n agent-platform get svc mock-sandbox >/dev/null 2>&1; then
  say "⏭️ SKIP：集群内无 mock-sandbox svc（仅 CI overlay 部署）——本地先 kubectl apply -f manifests/ci/mock-sandbox.yaml"
  exit 2
fi
# 池名-服务绑定契约（skills-syncd §5.2 C-1/G1）：池名必须 {服务k8s名}-pool 且服务行存在，
# 服务行经 kubectl mysql 轻量造数——无集群访问时整模块 SKIP（校验矩阵单测已覆盖本地口径）
if [ "$KUBE_OK" != "1" ]; then
  say "⏭️ SKIP：池名-服务绑定契约需 kubectl 预置服务行，无集群访问时整模块 SKIP"
  exit 2
fi

WS=$(seed_ws "$TOKEN" "m12-ws-$TS")
WS2=$(seed_ws "$TOKEN" "m12-ws2-$TS")
[ -n "$WS2" ] || { say "❌ WS2 创建失败"; exit 1; }
SVC="oaf-m12-svc-$TS"
SVC2="oaf-m12-svc2-$TS"
POOL="$SVC-pool"
POOL2="$SVC2-pool"
# 平台 backend 可达的 mock 地址（集群内 svc；本地/CI 同形）
OPENSANDBOX_URL="${OPENSANDBOX_URL:-mock-sandbox.agent-platform.svc.cluster.local:8090}"
OPENSANDBOX_KEY="${OPENSANDBOX_KEY:-m12-e2e-key}"

cleanup_m12() {
  code DELETE /admin/sandboxes/pools/$POOL "" "$TOKEN" >/dev/null 2>&1
  code DELETE /admin/sandboxes/pools/$POOL2 "" "$TOKEN" >/dev/null 2>&1
  for id in $M12_SBIDS; do
    code DELETE /admin/sandboxes/$id "" "$TOKEN" >/dev/null 2>&1
  done
  if [ "$KUBE_OK" = "1" ]; then
    kubectl -n $KNS exec deploy/oaf-mysql -- mysql -h127.0.0.1 -uoaf -pOafPlatform2026 oaf_platform \
      -e "DELETE FROM services WHERE k8s_name IN ('$SVC','$SVC2'); DELETE FROM workspace_members WHERE workspace_id IN ($WS,$WS2); DELETE FROM workspaces WHERE id IN ($WS,$WS2);" >/dev/null 2>&1
  fi
  mock_node "POST /admin/fault" '{"reset":true}' >/dev/null 2>&1
}
M12_SBIDS=""
trap cleanup_m12 EXIT

# mock_node <path-with-method> [body]：在 mock-sandbox 容器内以 node fetch 直连本机 8090
# （node:22-alpine 无 curl；mock 为 ClusterIP，宿主机不可直达，只能 exec 进容器）。
mock_node() {
  [ "$KUBE_OK" = "1" ] || return 1
  local spec="$1" payload="${2:-}"
  local method="${spec%% *}" path="${spec#* }"
  kubectl -n $KNS exec deploy/mock-sandbox -- node -e "
    fetch('http://127.0.0.1:8090${path}',{method:'${method}',headers:{'content-type':'application/json'},body:process.argv[1]||undefined})
      .then(async r=>{const b=await r.text();console.log(r.status+' '+b.slice(0,4000))}).catch(e=>{console.log('ERR '+e.message);process.exit(1)})
  " "$payload" 2>/dev/null
}

# ---- ① platform_config 沙箱组模板（新 3 键 + placeholder 30）----
TPL=$(req GET /platform-config "" "$TOKEN")
for k in SANDBOX_POOL_REF SANDBOX_USER_QUOTA SANDBOX_RENEW_INTERVAL_SECONDS; do
  assert_contains "模板含沙箱池化新键 $k" "$TPL" "\"$k\""
done
assert_contains "SANDBOX_TIMEOUT_MINUTES placeholder 升级为 30（§4.7）" "$(echo "$TPL" | python3 -c "
import sys,json
d=json.load(sys.stdin)
for g in d['data']['groups']:
    if g['name']=='sandbox':
        for f in g['fields']:
            if f['envKey']=='SANDBOX_TIMEOUT_MINUTES':
                print(f.get('placeholder') or '')")" "30"
# 配置沙箱组（URL/APIKey → platform_config；backend 代理由此获得事实源）
PC=$(code PUT /platform-config "{\"values\":{\"OPENSANDBOX_SERVER_URL\":\"$OPENSANDBOX_URL\",\"OPENSANDBOX_API_KEY\":\"$OPENSANDBOX_KEY\"}}" "" "$TOKEN")
assert_eq "platform_config 写入沙箱 URL/APIKey" "$PC" "200"

# 池契约前置：预置服务行（绑定独立 WS2，避免污染 ③ 的「空服务集合」断言；SVC 稍后 UPDATE 归入 WS）
kubectl -n $KNS exec deploy/oaf-mysql -- mysql -h127.0.0.1 -uoaf -pOafPlatform2026 oaf_platform \
  -e "INSERT INTO services (k8s_name, display_name, image, env_json, replicas, workspace_id, status, created_at, updated_at)
      VALUES ('$SVC', 'M12 svc', 'img', '{}', 1, $WS2, 'running', NOW(), NOW());" >/dev/null 2>&1
kubectl -n $KNS exec deploy/oaf-mysql -- mysql -h127.0.0.1 -uoaf -pOafPlatform2026 oaf_platform \
  -e "INSERT INTO services (k8s_name, display_name, image, env_json, replicas, workspace_id, status, created_at, updated_at)
      VALUES ('$SVC2', 'M12 svc2', 'img', '{}', 1, $WS2, 'running', NOW(), NOW());" >/dev/null 2>&1

# ---- ② admin 代理：summary / 创建池（预设模板）/ 容量调整 / 沙箱列表 ----
SUM=$(req GET /admin/sandboxes/summary "" "$TOKEN")
assert_contains "summary 聚合可读（pools/warmAvailable/allocated/runningSandboxes）" "$SUM" '"runningSandboxes"'

CR=$(code POST /admin/sandboxes/pools "{\"name\":\"$POOL\",\"capacitySpec\":{\"bufferMin\":1,\"bufferMax\":2,\"poolMin\":1,\"poolMax\":3}}" "" "$TOKEN")
assert_eq "创建池 → 201（预设模板由后端生成）" "$CR" "201"
CR2=$(code POST /admin/sandboxes/pools "{\"name\":\"$POOL2\",\"image\":\"img-x:1\",\"capacitySpec\":{\"bufferMin\":1,\"bufferMax\":2,\"poolMin\":1,\"poolMax\":3}}" "" "$TOKEN")
assert_eq "创建池（显式镜像）→ 201" "$CR2" "201"
BAD=$(code POST /admin/sandboxes/pools "{\"name\":\"m12-pool-$TS\",\"capacitySpec\":{\"bufferMin\":1,\"bufferMax\":2,\"poolMin\":1,\"poolMax\":3}}" "" "$TOKEN")
assert_eq "池名不符 {服务k8s名}-pool 契约 → 400（skills-syncd C-1）" "$BAD" "400"
BAD=$(code POST /admin/sandboxes/pools "{\"name\":\"oaf-m12-ghost-$TS-pool\",\"capacitySpec\":{\"bufferMin\":1,\"bufferMax\":2,\"poolMin\":1,\"poolMax\":3}}" "" "$TOKEN")
assert_eq "绑定服务不存在的池名 → 400（skills-syncd C-1）" "$BAD" "400"
BAD=$(code POST /admin/sandboxes/pools "{\"name\":\"$POOL\",\"capacitySpec\":{\"bufferMin\":1,\"bufferMax\":2,\"poolMin\":1,\"poolMax\":3}}" "" "$TOKEN")
assert_eq "同名创建池 → 409 透传（容量合法，仅 name 重复；容量链破坏是另一条断言）" "$BAD" "409"
BAD=$(code POST /admin/sandboxes/pools "{\"name\":\"Bad_Name\",\"capacitySpec\":{\"bufferMin\":1,\"bufferMax\":2,\"poolMin\":1,\"poolMax\":3}}" "" "$TOKEN")
assert_eq "非法池名 → 400" "$BAD" "400"
BAD=$(code POST /admin/sandboxes/pools "{\"name\":\"p-$TS\",\"capacitySpec\":{\"bufferMin\":3,\"bufferMax\":2,\"poolMin\":1,\"poolMax\":4}}" "" "$TOKEN")
assert_eq "容量链破坏 → 400" "$BAD" "400"

PL=$(req GET /admin/sandboxes/pools "" "$TOKEN")
assert_contains "池列表含新建池" "$PL" "$POOL"
UP=$(code PUT /admin/sandboxes/pools/$POOL "{\"capacitySpec\":{\"bufferMin\":2,\"bufferMax\":2,\"poolMin\":1,\"poolMax\":3}}" "" "$TOKEN")
assert_eq "容量调整（PUT 仅 capacitySpec）→ 200" "$UP" "200"
ONE=$(req GET /admin/sandboxes/pools/$POOL "" "$TOKEN")
assert_contains "单池详情回读新容量" "$ONE" '"bufferMin":2'

# mock 收到正确载荷（预设模板：initContainers×2 + capacitySpec 原样；KUBE_OK 门控）
if [ "$KUBE_OK" = "1" ]; then
  STATS=$(mock_node "GET /stats")
  assert_contains "mock 收到建池载荷（池名）" "$STATS" "\"name\":\"$POOL\""
  assert_contains "mock 收到预设模板（task-executor-installer initContainer）" "$STATS" "task-executor-installer"
  assert_contains "mock 收到预设模板（execd-installer initContainer）" "$STATS" "execd-installer"
  assert_contains "mock 收到主容器 task-executor 监听命令（官方 sample 形状，d18）" "$STATS" "task-executor -listen-addr"
  assert_contains "mock 收到容量四键" "$STATS" '"bufferMin":1'
else
  skip "mock 载荷断言需 kubectl（KUBE_OK=0）"
fi

# 沙箱造数（直连 mock 建 3 个：2 个本 ws 服务名 + 1 个无关服务；KUBE_OK 门控）
if [ "$KUBE_OK" = "1" ]; then
  for i in 1 2; do
    R=$(mock_node "POST /v1/sandboxes" "{\"metadata\":{\"service\":\"$SVC\",\"session_id\":\"m12-sess-$i\"},\"timeout\":1800}")
    SID=$(echo "$R" | sed 's/^[0-9]* //' | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])" 2>/dev/null)
    [ -n "$SID" ] && M12_SBIDS="$M12_SBIDS $SID"
  done
  R=$(mock_node "POST /v1/sandboxes" "{\"metadata\":{\"service\":\"oaf-other-$TS\"},\"timeout\":1800}")
  OID=$(echo "$R" | sed 's/^[0-9]* //' | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])" 2>/dev/null)
  [ -n "$OID" ] && M12_SBIDS="$M12_SBIDS $OID"
  LB=$(req GET "/admin/sandboxes?state=Running" "" "$TOKEN")
  assert_contains "沙箱列表含造数实例" "$LB" "$SVC"
  N=$(echo "$LB" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(sum(1 for it in d['data']['items'] if (it.get('metadata') or {}).get('service')=='$SVC'))")
  assert_eq "state=Running 过滤后本服务实例 2 条" "$N" "2"
else
  skip "沙箱造数与列表断言需 kubectl（KUBE_OK=0）"
fi

# ---- ③ console 只读端点（metadata.service ∈ ws 服务集合过滤）----
CS=$(req GET /console/workspaces/$WS/sandboxes "" "$TOKEN")
N=$(echo "$CS" | python3 -c "import sys,json;d=json.load(sys.stdin);print(len(d['data']))" 2>/dev/null)
assert_eq "空服务集合 → console 过滤为空（不泄露他服务实例）" "$N" "0"
if [ "$KUBE_OK" = "1" ] && [ -n "$M12_SBIDS" ]; then
  kubectl -n $KNS exec deploy/oaf-mysql -- mysql -h127.0.0.1 -uoaf -pOafPlatform2026 oaf_platform \
    -e "UPDATE services SET workspace_id=$WS, updated_at=NOW() WHERE k8s_name='$SVC';" >/dev/null 2>&1
  CS=$(req GET /console/workspaces/$WS/sandboxes "" "$TOKEN")
  N=$(echo "$CS" | python3 -c "import sys,json;d=json.load(sys.stdin);print(len(d['data']))" 2>/dev/null)
  assert_eq "ws 服务行就位 → console 过滤出 2 条本服务实例" "$N" "2"
  OTHER=$(echo "$CS" | grep -c "oaf-other-$TS" || true)
  assert_eq "无关服务实例被过滤" "$OTHER" "0"
  FIELDS=$(echo "$CS" | python3 -c "
import sys,json
d=json.load(sys.stdin)['data'][0]
print(','.join(sorted(d.keys())))")
  assert_eq "console 只读投影字段（id/state/service/sessionId/expiresAt/createdAt）" "$FIELDS" "createdAt,expiresAt,id,service,sessionId,state"
  OP=$(code DELETE /console/workspaces/$WS/sandboxes/nonexistent "" "$TOKEN")
  assert_eq "console 无操作端点（DELETE → 404）" "$OP" "404"
  OP=$(code POST /console/workspaces/$WS/sandboxes "" "$TOKEN")
  assert_eq "console 无操作端点（POST → 非 200）" "$OP" "404"
else
  skip "console 正向过滤断言需 kubectl 造服务行（KUBE_OK=0）"
fi

# ---- 审计：管理操作各产生一条审计记录（PlatformConfigEvent 同款先例）----
if [ -n "$M12_SBIDS" ]; then
  FIRST_SID=$(echo $M12_SBIDS | awk '{print $1}')
  DS=$(code DELETE /admin/sandboxes/$FIRST_SID "" "$TOKEN")
  assert_eq "销毁沙箱 → 200（上游 204）" "$DS" "200"
  M12_SBIDS=$(echo $M12_SBIDS | tr ' ' '\n' | grep -v "^$FIRST_SID$" | tr '\n' ' ')
fi
AUD=$(req GET "/admin/audit-logs?page=1&size=50" "" "$TOKEN")
assert_contains "审计含 sandbox.pool.create" "$AUD" '"sandbox.pool.create"'
assert_contains "审计对象为本次池名" "$AUD" "$POOL"
assert_contains "审计含 sandbox.pool.update" "$AUD" '"sandbox.pool.update"'
if [ "$KUBE_OK" = "1" ] && [ -n "$(echo $M12_SBIDS | tr -d ' ')" ]; then
  assert_contains "审计含 sandbox.sandbox.delete" "$AUD" '"sandbox.sandbox.delete"'
fi

# ---- 池删除（确认对话为前端行为；API 层断言删除生效 + 审计）----
DP=$(code DELETE /admin/sandboxes/pools/$POOL2 "" "$TOKEN")
assert_eq "删除池 → 200（上游 204）" "$DP" "200"
PL=$(req GET /admin/sandboxes/pools "" "$TOKEN")
assert_not_contains "删除后列表不再含该池" "$PL" "$POOL2"
AUD=$(req GET "/admin/audit-logs?page=1&size=50" "" "$TOKEN")
assert_contains "审计含 sandbox.pool.delete" "$AUD" '"sandbox.pool.delete"'

# ---- ④ 上游故障注入：501 → 503（kubernetes runtime 文案）/ 401 → 502（指向 /settings）----
if [ "$KUBE_OK" = "1" ]; then
  mock_node "POST /admin/fault" '{"mode":"pools501","on":true}' >/dev/null
  R=$(code GET /admin/sandboxes/pools "" "$TOKEN")
  assert_eq "上游 501（K8S_POOL_NOT_SUPPORTED）→ 503" "$R" "503"
  MSG=$(req GET /admin/sandboxes/pools "" "$TOKEN")
  assert_contains "503 提示 kubernetes runtime 部署形态" "$MSG" "kubernetes"
  mock_node "POST /admin/fault" '{"mode":"pools501","on":false}' >/dev/null
  mock_node "POST /admin/fault" '{"mode":"unauthorized","on":true}' >/dev/null
  R=$(code GET /admin/sandboxes/pools "" "$TOKEN")
  assert_eq "上游 401 → 502（配置错误）" "$R" "502"
  MSG=$(req GET /admin/sandboxes/pools "" "$TOKEN")
  assert_contains "502 指向 /settings 修正 API Key" "$MSG" "settings"
  mock_node "POST /admin/fault" '{"reset":true}' >/dev/null
  R=$(code GET /admin/sandboxes/pools "" "$TOKEN")
  assert_eq "故障解除后恢复 200" "$R" "200"
else
  skip "上游故障注入断言需 kubectl（KUBE_OK=0）"
fi

# ---- ⑤ 未配置 URL → 503 + /settings 引导（platform_config 行直删；Required 键 API 拒绝清除）----
if [ "$KUBE_OK" = "1" ]; then
  kubectl -n $KNS exec deploy/oaf-mysql -- mysql -h127.0.0.1 -uoaf -pOafPlatform2026 oaf_platform \
    -e "DELETE FROM platform_config WHERE env_key IN ('OPENSANDBOX_SERVER_URL','OPENSANDBOX_API_KEY');" >/dev/null 2>&1
  R=$(code GET /admin/sandboxes/pools "" "$TOKEN")
  assert_eq "未配置 URL/APIKey → 503" "$R" "503"
  MSG=$(req GET /admin/sandboxes/pools "" "$TOKEN")
  assert_contains "503 引导先在 /settings 配置" "$MSG" "settings"
  # issue #297：console 侧同态——成员通用文案，不透 /settings 运维指引
  R=$(code GET /console/workspaces/$WS/sandboxes "" "$TOKEN")
  assert_eq "console 沙箱实例未配置 → 503（状态码保留）" "$R" "503"
  MSG=$(req GET /console/workspaces/$WS/sandboxes "" "$TOKEN")
  assert_contains "console 503 为成员通用文案" "$MSG" "平台管理员"
  assert_not_contains "console 503 不透 /settings 指引" "$MSG" "settings"
  code PUT /platform-config "{\"values\":{\"OPENSANDBOX_SERVER_URL\":\"$OPENSANDBOX_URL\",\"OPENSANDBOX_API_KEY\":\"$OPENSANDBOX_KEY\"}}" "" "$TOKEN" >/dev/null
  R=$(code GET /admin/sandboxes/pools "" "$TOKEN")
  assert_eq "配置恢复后恢复 200" "$R" "200"
  R=$(code GET /console/workspaces/$WS/sandboxes "" "$TOKEN")
  assert_eq "配置恢复后 console 端点恢复 200" "$R" "200"
else
  skip "未配置分支需 kubectl 直删 platform_config 行（KUBE_OK=0）"
fi

finish "M12-sandbox-admin"

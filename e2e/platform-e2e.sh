#!/usr/bin/env bash
# OAF 平台 E2E：场景 A（REST 发布主链路）+ 场景 B（全动作矩阵 + 异常路径）
# 前置：platform-backend 已部署于 agent-platform，ingress :30080 可达
set -u
BASE="${BASE:-http://localhost:30080/api/v1}"
FIXDIR="$(cd "$(dirname "$0")/fixtures" && pwd)"
NS=agent-platform
PASS=0; FAIL=0
declare -a FAILED_CASES

say()  { echo -e "\n\033[1;34m== $* ==\033[0m"; }
ok()   { PASS=$((PASS+1)); echo "  \033[32mPASS\033[0m $1"; }
bad()  { FAIL=$((FAIL+1)); FAILED_CASES+=("$1"); echo "  \033[31mFAIL\033[0m $1"; }
assert_eq() { # assert_eq <name> <got> <want>
  if [ "$2" == "$3" ]; then ok "$1"; else bad "$1 (got: $2, want: $3)"; fi
}
assert_contains() {
  case "$2" in *"$3"*) ok "$1";; *) bad "$1 (body missing: $3)";; esac
}

# 业务镜像(agent-framework)启动必需的运行时 env
source "$(dirname "$0")/../.env.secrets" 2>/dev/null || source /root/agent-manager/.env.secrets
CKPT_URL="jdbc:mysql://oaf-mysql.$NS.svc.cluster.local:3306/oaf_checkpoint?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
export RUNTIME_ENV="{\"LLM_API_KEY\":\"$LLM_API_KEY\",\"LLM_MODEL_ID\":\"$LLM_MODEL\",\"LLM_BASE_URL\":\"$LLM_ENDPOINT\",\"CHECKPOINT_JDBC_URL\":\"$CKPT_URL\",\"CHECKPOINT_USERNAME\":\"oaf\",\"CHECKPOINT_PASSWORD\":\"OafPlatform2026\"}"

api() { # api <method> <path> [json-body]
  local m=$1 p=$2 b=${3:-}
  if [ -n "$b" ]; then
    curl -s -X "$m" "$BASE$p" -H 'Content-Type: application/json' -d "$b"
  else
    curl -s -X "$m" "$BASE$p"
  fi
}
upload_zip() { curl -s -X POST "$BASE/packages" -F "file=@$1"; }

wait_status() { # wait_status <id> <want> <timeout_sec>
  local id=$1 want=$2 timeout=${3:-240} waited=0 got=""
  while true; do
    got=$(api GET "/services/$id" | jq -r '.data.status')
    [ "$got" == "$want" ] && return 0
    [ $waited -ge $timeout ] && { echo "timeout waiting $want (last=$got)"; return 1; }
    sleep 5; waited=$((waited+5))
  done
}
k8s_res() { kubectl -n $NS get "$@" >/dev/null 2>&1; }

cleanup_all() {
  say "清理遗留服务/包（保护 release-agent 平台组件）"
  for id in $(api GET /services | jq -r '.data[]? | select(.k8sName != "oaf-release-agent") | .id'); do
    api DELETE "/services/$id" >/dev/null
  done
  for id in $(api GET /packages | jq -r '.data[]?.id'); do
    api DELETE "/packages/$id" >/dev/null
  done
}

###############################################################################
cleanup_all

say "场景 A：REST 发布主链路"
UP=$(upload_zip "$FIXDIR/demo-agent-v1.zip")
PKG_ID=$(echo "$UP" | jq -r '.data.id')
assert_eq "A1 上传包返回 id" "$(echo "$UP" | jq -r '.code')" "0"
[ -n "$PKG_ID" ] && [ "$PKG_ID" != "null" ] && ok "A1.1 packageId=$PKG_ID" || bad "A1.1 无 packageId"

DET=$(api GET "/packages/$PKG_ID")
assert_eq "A2 包详情 slug"   "$(echo "$DET" | jq -r '.data.package.slug')" "e2e/demo"
assert_eq "A3 包详情 version" "$(echo "$DET" | jq -r '.data.package.version')" "1.0.0"
assert_contains "A4 详情含 AGENTS.md 正文" "$(echo "$DET" | jq -r '.data.agentsMd')" "# E2E Demo"

PUB=$(api POST /services "{\"packageId\":$PKG_ID,\"image\":\"agent-framework:latest\",\"env\":$(python3 -c "import json,os;d=json.loads(os.environ['RUNTIME_ENV']);d['LOG_LEVEL']='info';print(json.dumps(d))")}")
SVC_ID=$(echo "$PUB" | jq -r '.data.id')
K8S_NAME=$(echo "$PUB" | jq -r '.data.k8sName')
assert_eq "A5 发布受理 deploying" "$(echo "$PUB" | jq -r '.data.status')" "deploying"
assert_eq "A6 K8s 名规范" "$K8S_NAME" "oaf-e2e-demo"

if wait_status "$SVC_ID" running 300; then ok "A7 状态到达 running"; else bad "A7 未到 running"; fi
DETAIL=$(api GET "/services/$SVC_ID")
assert_contains "A8 注册信息含 Agent Card" "$DETAIL" '"registeredName":"E2E Demo Agent"'
assert_eq "A9 注册版本" "$(echo "$DETAIL" | jq -r '.data.registeredVersion')" "1.0.0"

k8s_res deploy $K8S_NAME      && ok "A10 Deployment 存在"     || bad "A10 Deployment 缺失"
k8s_res svc $K8S_NAME-svc     && ok "A11 Service 存在"        || bad "A11 Service 缺失"
k8s_res ingress $K8S_NAME     && ok "A12 Ingress 存在"        || bad "A12 Ingress 缺失"
k8s_res cm $K8S_NAME-env      && ok "A13 env ConfigMap 存在"  || bad "A13 ConfigMap 缺失"

EXT=$(curl -s http://localhost:30080/agent/e2e-demo/.well-known/agent-card.json)
assert_contains "A14 外部经 Ingress 拉取 agent-card" "$EXT" '"name"'
POD_DIR=$(kubectl -n $NS exec deployment/platform-backend -- ls /data/packages 2>/dev/null | grep -c "^$PKG_ID$")
assert_eq "A15 PVC 内包目录存在" "$POD_DIR" "1"

say "场景 B：全动作矩阵 + 异常路径"
MERGED_ENV=$(RUNTIME_ENV="$RUNTIME_ENV" python3 -c "
import json,os
d=json.loads(os.environ['RUNTIME_ENV'])
d.update({'LOG_LEVEL':'warn','NEW_KEY':'v1'})
print(json.dumps({'env':d}))")
ENV_UPD=$(api PATCH "/services/$SVC_ID/env" "$MERGED_ENV")
assert_eq "B1 env 更新受理" "$(echo "$ENV_UPD" | jq -r '.data.status')" "deploying"
CM_VAL=$(kubectl -n $NS get cm oaf-e2e-demo-env -o jsonpath='{.data.LOG_LEVEL}')
assert_eq "B2 ConfigMap 已更新" "$CM_VAL" "warn"
if wait_status "$SVC_ID" running 300; then ok "B3 env 更新后回到 running"; else bad "B3 未回 running"; fi

UP2=$(upload_zip "$FIXDIR/demo-agent-v2.zip")
PKG2_ID=$(echo "$UP2" | jq -r '.data.id')
RE=$(api POST "/services/$SVC_ID/republish" "{\"packageId\":$PKG2_ID}")
assert_eq "B4 republish 受理" "$(echo "$RE" | jq -r '.data.status')" "deploying"
SUBPATH=$(kubectl -n $NS get deploy oaf-e2e-demo -o jsonpath='{.spec.template.spec.containers[0].volumeMounts[0].subPath}')
assert_eq "B5 subPath 已切换新包目录" "$SUBPATH" "packages/$PKG2_ID"
RC1=$(api GET "/packages/$PKG_ID"  | jq -r '.data.package.refCount')
RC2=$(api GET "/packages/$PKG2_ID" | jq -r '.data.package.refCount')
assert_eq "B6 引用计数迁移" "${RC1}->${RC2}" "0->1"
if wait_status "$SVC_ID" running 300; then ok "B7 republish 后 running（注册版本应升级）"; else bad "B7 未回 running"; fi
VER=$(api GET "/services/$SVC_ID" | jq -r '.data.registeredVersion')
assert_eq "B8 注册版本为 v1.1.0" "$VER" "1.1.0"

UN=$(api POST "/services/$SVC_ID/unpublish")
assert_eq "B9 unpublish → stopped" "$(echo "$UN" | jq -r '.data.status')" "stopped"
k8s_res deploy $K8S_NAME  && bad "B10 下线后 Deployment 应删除" || ok "B10 Deployment 已删"
k8s_res ingress $K8S_NAME && bad "B11 下线后 Ingress 应删除"    || ok "B11 Ingress 已删"
k8s_res cm $K8S_NAME-env  && ok "B12 下线保留 ConfigMap"         || bad "B12 ConfigMap 不应删除"
RG=$(api POST "/services/$SVC_ID/publish")
assert_eq "B13 重新上线受理" "$(echo "$RG" | jq -r '.data.status')" "deploying"
if wait_status "$SVC_ID" running 300; then ok "B14 再次上线 running"; else bad "B14 未回 running"; fi

RR=$(api POST "/services/$SVC_ID/register")
assert_eq "B15 手动重注册成功" "$(echo "$RR" | jq -r '.data.status')" "running"

say "异常路径"
BADZIP=/tmp/opencode/bad.zip; echo notazip > $BADZIP
W=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/packages" -F "file=@$BADZIP")
assert_eq "E1 非法 zip → 400" "$W" "400"
W=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/packages" -F "file=@$FIXDIR/bad-no-agentsmd.zip")
assert_eq "E2 缺 AGENTS.md → 400" "$W" "400"
W=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/services" \
  -H 'Content-Type: application/json' -d "{\"packageId\":$PKG_ID,\"image\":\"evil:x\"}")
assert_eq "E3 镜像不在列表 → 400" "$W" "400"
W=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/services" -H 'Content-Type: application/json' \
  -d "{\"packageId\":$PKG_ID,\"env\":{\"AGENT_CONFIG_DIR\":\"/hack\"}}")
assert_eq "E4 保留键 env → 400" "$W" "400"
W=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/services" -H 'Content-Type: application/json' \
  -d '{"packageId":99999}')
assert_eq "E5 包不存在 → 404" "$W" "404"

say "删除与零残留验证"
api DELETE "/services/$SVC_ID" >/dev/null
sleep 2
k8s_res deploy $K8S_NAME  && bad "D1 Deployment 残留"  || ok "D1 Deployment 清理"
k8s_res svc $K8S_NAME-svc && bad "D2 Service 残留"     || ok "D2 Service 清理"
k8s_res ingress $K8S_NAME && bad "D3 Ingress 残留"     || ok "D3 Ingress 清理"
k8s_res cm $K8S_NAME-env  && bad "D4 ConfigMap 残留"   || ok "D4 ConfigMap 清理"
W=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/services/$SVC_ID")
assert_eq "D5 服务记录已删(404)" "$W" "404"
# 服务删除后配置包保留（可复用），显式删除后目录才清理
api DELETE "/packages/$PKG_ID"  >/dev/null
api DELETE "/packages/$PKG2_ID" >/dev/null
sleep 1
# 三方一致性：PVC 内目录数应等于库内剩余包数（含受保护的 release-agent 包）
LEFT=$(kubectl -n $NS exec deployment/platform-backend -- sh -c 'ls /data/packages 2>/dev/null | wc -l' | tr -d '\r')
DBPKGS=$(api GET /packages | jq -r '.data | length')
assert_eq "D6 PVC 目录与库内包数一致" "$LEFT" "$DBPKGS"

say "结果汇总"
echo "----------------------------------------"
echo "PASS: $PASS  FAIL: $FAIL"
if [ $FAIL -gt 0 ]; then printf 'failed: %s\n' "${FAILED_CASES[@]}"; exit 1; fi
exit 0

#!/usr/bin/env bash
# 平台默认配置 E2E（R3 语义：默认配置仅作发布/编辑 env 的表单默认填入，不经 envFrom 注入，
# 不影响存量服务）。覆盖：模板 schema / PUT 掩码与校验 / defaults 数据源 / 空 env 发布
# 不被平台配置影响（隔离性）/ 填入流端到端 / 敏感路由 / sticky 与空串删除 / 删除零残留。
# 前置：platform-backend(≥v5) 已部署，:30080 可达，.env.secrets 提供真实 LLM 配置。
set -u
BASE="${BASE:-http://localhost:30080/api/v1}"
FIXDIR="$(cd "$(dirname "$0")/fixtures" && pwd)"
NS=agent-platform
PASS=0; FAIL=0
declare -a FAILED_CASES

say()  { echo -e "\n\033[1;34m== $* ==\033[0m"; }
ok()   { PASS=$((PASS+1)); echo "  \033[32mPASS\033[0m $1"; }
bad()  { FAIL=$((FAIL+1)); FAILED_CASES+=("$1"); echo "  \033[31mFAIL\033[0m $1"; }
assert_eq() { if [ "$2" == "$3" ]; then ok "$1"; else bad "$1 (got: $2, want: $3)"; fi; }
assert_contains() { case "$2" in *"$3"*) ok "$1";; *) bad "$1 (body missing: $3)";; esac; }
assert_not_contains() { case "$2" in *"$3"*) bad "$1 (不应出现: $3)";; *) ok "$1";; esac; }

source "$(dirname "$0")/../.env.secrets" 2>/dev/null || source /root/agent-manager/.env.secrets
CKPT_URL="jdbc:mysql://oaf-mysql.$NS.svc.cluster.local:3306/oaf_checkpoint?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
REDIS_URL="redis://oaf-redis.$NS.svc.cluster.local:6379"
# 模板敏感键（backend platformconfig 模板的对拍快照；用于期望值计算，值永不打印）
SENSITIVE_KEYS='["LLM_API_KEY","CHECKPOINT_PASSWORD","AGENT_REDIS_URL","OPENSANDBOX_API_KEY"]'

api() { local m=$1 p=$2 b=${3:-}
  if [ -n "$b" ]; then curl -s -X "$m" "$BASE$p" -H 'Content-Type: application/json' -d "$b"
  else curl -s -X "$m" "$BASE$p"; fi
}
wait_status() { local id=$1 want=$2 timeout=${3:-300} waited=0 got=""
  while true; do
    got=$(api GET "/services/$id" | jq -r '.data.status')
    [ "$got" == "$want" ] && return 0
    [ $waited -ge $timeout ] && { echo "timeout waiting $want (last=$got)"; return 1; }
    sleep 5; waited=$((waited+5))
  done
}
k8s_res() { kubectl -n $NS get "$@" >/dev/null 2>&1; }

# 只清理本脚本创建的服务（前缀作用域），不动其他服务
for id in $(api GET /services | jq -r '.data[]? | select(.k8sName | startswith("oaf-cfgsecret-e2e")) | .id'); do
  api DELETE "/services/$id" >/dev/null
done

###############################################################################
say "场景 P：平台默认配置（模板 schema + PUT 掩码校验 + defaults 数据源）"
PC=$(api GET /platform-config)
assert_eq "P1 GET schema code 0" "$(echo "$PC" | jq -r '.code')" "0"
assert_eq "P2 四组分组" "$(echo "$PC" | jq -r '.data.groups | length')" "4"
assert_eq "P3 SANDBOX_ENABLED 不在模板" "$(echo "$PC" | jq '[.data.groups[].fields[].envKey] | index("SANDBOX_ENABLED") == null')" "true"

PUT=$(api PUT /platform-config "{\"values\":{\"LLM_API_KEY\":\"$LLM_API_KEY\",\"LLM_BASE_URL\":\"$LLM_ENDPOINT\",\"LLM_MODEL_ID\":\"$LLM_MODEL\",\"LLM_TEMPERATURE\":\"0.3\"}}")
assert_eq "P4 PUT code 0" "$(echo "$PUT" | jq -r '.code')" "0"
assert_not_contains "P5 PUT/展示视图不回显敏感值" "$PUT" "$LLM_API_KEY"
W=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$BASE/platform-config" -H 'Content-Type: application/json' -d '{"values":{"NOT_IN_TEMPLATE":"x"}}')
assert_eq "P6 未知键 → 400" "$W" "400"
W=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$BASE/platform-config" -H 'Content-Type: application/json' -d '{"values":{"LLM_API_KEY":""}}')
assert_eq "P7 清除必填键 → 400" "$W" "400"
DFLT=$(api GET /platform-config/defaults)
assert_eq "P8 defaults 含敏感与非敏感值" "$(echo "$DFLT" | jq -r --arg k "$LLM_API_KEY" '.data.values.LLM_API_KEY == $k and .data.values.LLM_MODEL_ID != ""')" "true"

say "场景 R：隔离性——最小 env 发布不被平台配置影响（框架仅依赖 checkpoint/redis）"
UP=$(curl -s -X POST "$BASE/packages" -F "file=@$FIXDIR/demo-agent-v1.zip")
PKG_ID=$(echo "$UP" | jq -r '.data.id')
MIN_ENV="{\"CHECKPOINT_JDBC_URL\":\"$CKPT_URL\",\"CHECKPOINT_USERNAME\":\"oaf\",\"CHECKPOINT_PASSWORD\":\"OafPlatform2026\",\"AGENT_REDIS_URL\":\"$REDIS_URL\",\"LOG_LEVEL\":\"info\"}"
PUB=$(api POST /services "{\"packageId\":$PKG_ID,\"name\":\"cfgsecret-e2e-a\",\"env\":$MIN_ENV}")
S1=$(echo "$PUB" | jq -r '.data.id'); K1=$(echo "$PUB" | jq -r '.data.k8sName')
if wait_status "$S1" running 300; then ok "R1 最小 env 发布 → running（无需平台默认值）"; else bad "R1 未到 running"; fi
assert_eq "R2 envFrom 仅两源（无平台对象）" "$(kubectl -n $NS get deploy $K1 -o jsonpath='{.spec.template.spec.containers[0].envFrom}' | jq 'length')" "2"
assert_eq "R3 Pod 无平台默认 LLM 键（未被注入）" "$(kubectl -n $NS exec deploy/$K1 -- sh -c 'env' 2>/dev/null | grep -cE '^LLM_API_KEY=')" "0"
k8s_res cm oaf-platform-default-config && bad "R4 平台 CM 不应存在" || ok "R4 平台 CM 不存在"
k8s_res secret oaf-platform-default-secret && bad "R5 平台 Secret 不应存在" || ok "R5 平台 Secret 不存在"

say "场景 S：默认填入流——以 defaults 值作为 env 发布（模拟向导预填提交）"
DFLT_VALUES=$(echo "$DFLT" | jq -c '.data.values')
# 期望 envJson = defaults 剔除全部模板敏感键（后端路由语义）
WANT_PLAIN=$(echo "$DFLT_VALUES" | jq -c --argjson sk "$SENSITIVE_KEYS" 'to_entries | map(select(.key as $k | $sk | index($k) | not)) | from_entries')
PUB2=$(api POST /services "{\"packageId\":$PKG_ID,\"name\":\"cfgsecret-e2e-b\",\"env\":$DFLT_VALUES}")
S2=$(echo "$PUB2" | jq -r '.data.id'); K2=$(echo "$PUB2" | jq -r '.data.k8sName')
assert_eq "S1 envJson 剔除全部敏感键（语义比较，Go 侧 & 转义为 \u0026 不影响）" \
  "$(echo "$PUB2" | jq -r --argjson want "$WANT_PLAIN" '.data.envJson | fromjson == $want')" "true"
if wait_status "$S2" running 300; then ok "S2 defaults 填入发布 → running（真实配置全链路）"; else bad "S2 未到 running"; fi
assert_eq "S3 敏感键落服务 Secret" "$(kubectl -n $NS get secret $K2-env-secret -o json | jq -r '.data.LLM_API_KEY | @base64d')" "$LLM_API_KEY"
DETAIL=$(api GET "/services/$S2")
assert_not_contains "S4 详情不泄漏明文" "$DETAIL" "$LLM_API_KEY"
assert_contains "S5 envSecretKeys 掩码视图" "$DETAIL" '"key":"LLM_API_KEY","hasValue":true'

say "场景 T：sticky 三态 + 空串删除回落"
PATCH=$(curl -s -X PATCH "$BASE/services/$S2/env" -H 'Content-Type: application/json' -d '{"env":{"LLM_MODEL_ID":"other-model"}}')
assert_eq "T1 仅改非敏感键受理" "$(echo "$PATCH" | jq -r '.code')" "0"
wait_status "$S2" running 300 >/dev/null
assert_eq "T2 sticky：服务 Secret 敏感键保持" "$(kubectl -n $NS get secret $K2-env-secret -o json | jq -r '.data.LLM_API_KEY | @base64d')" "$LLM_API_KEY"
PATCH2=$(curl -s -X PATCH "$BASE/services/$S2/env" -H 'Content-Type: application/json' -d '{"env":{"LLM_API_KEY":"","LLM_MODEL_ID":"other-model"}}')
assert_eq "T3 空串删除受理" "$(echo "$PATCH2" | jq -r '.code')" "0"
wait_status "$S2" running 300 >/dev/null
assert_eq "T4 删除后服务 Secret 不含该键" "$(kubectl -n $NS get secret $K2-env-secret -o json | jq -r '.data | has("LLM_API_KEY")')" "false"

say "场景 U：改平台默认不影响存量服务"
GEN_BEFORE=$(kubectl -n $NS get deploy $K2 -o jsonpath='{.metadata.generation}')
ST_BEFORE=$(api GET "/services/$S2" | jq -r '.data.status')
api PUT /platform-config '{"values":{"LLM_TEMPERATURE":"0.9"}}' >/dev/null
sleep 6
assert_eq "U1 服务状态无变化（未触发重启）" "$(api GET "/services/$S2" | jq -r '.data.status')" "$ST_BEFORE"
assert_eq "U2 Deployment generation 不变" "$(kubectl -n $NS get deploy $K2 -o jsonpath='{.metadata.generation}')" "$GEN_BEFORE"
DFLT2=$(api GET /platform-config/defaults | jq -r '.data.values.LLM_TEMPERATURE')
assert_eq "U3 新默认值仅反映在 defaults 端点" "$DFLT2" "0.9"

say "场景 V：删除零残留（含服务 Secret）"
api DELETE "/services/$S1" >/dev/null; api DELETE "/services/$S2" >/dev/null
sleep 3
LEFT=$(kubectl -n $NS get secret,cm,deploy,ingress -o name 2>/dev/null | grep -c "cfgsecret-e2e")
assert_eq "V1 集群零残留（含服务 Secret）" "$LEFT" "0"

echo -e "\n\033[1;34m== 结果汇总 ==\033[0m"
echo "----------------------------------------"
echo "PASS: $PASS  FAIL: $FAIL"
[ $FAIL -gt 0 ] && { echo "failed: ${FAILED_CASES[*]}"; exit 1; }
exit 0

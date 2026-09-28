#!/usr/bin/env bash
# 平台默认配置 Secret 化 E2E：模板拆分渲染 / 服务敏感路由 / sticky 三态 / 空串删除回落 /
# apply-restart / 删除零残留。设计见 docs/design/platform-default-config-secret-design.md §8。
# 前置：platform-backend(≥v4) 已部署，:30080 可达，.env.secrets 提供真实 LLM 配置。
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

# 只清理本脚本创建的服务（前缀作用域），不动其他服务与平台对象
for id in $(api GET /services | jq -r '.data[]? | select(.k8sName | startswith("oaf-cfgsecret-e2e")) | .id'); do
  api DELETE "/services/$id" >/dev/null
done

###############################################################################
say "场景 P：平台默认配置（模板 schema + PUT 拆分渲染）"
PC=$(api GET /platform-config)
assert_eq "P1 GET schema code 0" "$(echo "$PC" | jq -r '.code')" "0"
assert_eq "P2 四组分组" "$(echo "$PC" | jq -r '.data.groups | length')" "4"
assert_eq "P3 敏感键 ≥4" "$(echo "$PC" | jq '[.data.groups[].fields[] | select(.sensitive==true)] | length >= 4')" "true"
assert_eq "P4 SANDBOX_ENABLED 不在模板" "$(echo "$PC" | jq '[.data.groups[].fields[].envKey] | index("SANDBOX_ENABLED") == null')" "true"

PUT=$(api PUT /platform-config "{\"values\":{\"LLM_API_KEY\":\"$LLM_API_KEY\",\"LLM_BASE_URL\":\"$LLM_ENDPOINT\",\"LLM_MODEL_ID\":\"$LLM_MODEL\",\"LLM_TEMPERATURE\":\"0.3\",\"CHECKPOINT_JDBC_URL\":\"$CKPT_URL\",\"CHECKPOINT_USERNAME\":\"oaf\",\"CHECKPOINT_PASSWORD\":\"OafPlatform2026\",\"AGENT_REDIS_URL\":\"$REDIS_URL\"}}")
assert_eq "P5 PUT code 0" "$(echo "$PUT" | jq -r '.code')" "0"
assert_not_contains "P6 PUT 响应不回显敏感值" "$PUT" "$LLM_API_KEY"
GET2=$(api GET /platform-config)
assert_not_contains "P7 GET 响应不回显敏感值" "$GET2" "$LLM_API_KEY"
assert_eq "P8 敏感键仅 hasValue" "$(echo "$GET2" | jq -r '[.data.groups[].fields[] | select(.envKey=="LLM_API_KEY")][0] | "\(.hasValue)/\(.value == null)"')" "true/true"
assert_eq "P9 非敏感键回填" "$(echo "$GET2" | jq -r '[.data.groups[].fields[] | select(.envKey=="LLM_MODEL_ID")][0].value')" "$LLM_MODEL"
W=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$BASE/platform-config" -H 'Content-Type: application/json' -d '{"values":{"NOT_IN_TEMPLATE":"x"}}')
assert_eq "P10 未知键 → 400" "$W" "400"
W=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$BASE/platform-config" -H 'Content-Type: application/json' -d '{"values":{"LLM_API_KEY":""}}')
assert_eq "P11 清除必填键 → 400" "$W" "400"

say "场景 Q：集群拆分渲染（Secret 敏感 / CM 非敏感）"
SEC_KEYS=$(kubectl -n $NS get secret oaf-platform-default-secret -o json | jq -r '.data | keys | join(",")')
CM_KEYS=$(kubectl -n $NS get cm oaf-platform-default-config -o json | jq -r '.data | keys | join(",")')
for k in LLM_API_KEY CHECKPOINT_PASSWORD AGENT_REDIS_URL; do
  case "$SEC_KEYS" in *$k*) ok "Q1 Secret 含 $k";; *) bad "Q1 Secret 缺 $k";; esac
done
for k in CHECKPOINT_JDBC_URL LLM_MODEL_ID LLM_TEMPERATURE; do
  case "$SEC_KEYS" in *$k*) bad "Q2 Secret 不应含 $k";; *) ok "Q2 Secret 不含 $k";; esac
done
for k in CHECKPOINT_JDBC_URL LLM_MODEL_ID; do
  case "$CM_KEYS" in *$k*) ok "Q3 CM 含 $k";; *) bad "Q3 CM 缺 $k";; esac
done
for k in LLM_API_KEY CHECKPOINT_PASSWORD AGENT_REDIS_URL; do
  case "$CM_KEYS" in *$k*) bad "Q4 CM 不应含 $k";; *) ok "Q4 CM 不含 $k";; esac
done

say "场景 R：发布不带 env → 平台默认值跑通全链路"
UP=$(curl -s -X POST "$BASE/packages" -F "file=@$FIXDIR/demo-agent-v1.zip")
PKG_ID=$(echo "$UP" | jq -r '.data.id')
PUB=$(api POST /services "{\"packageId\":$PKG_ID,\"name\":\"cfgsecret-e2e-a\"}")
S1=$(echo "$PUB" | jq -r '.data.id'); K1=$(echo "$PUB" | jq -r '.data.k8sName')
assert_eq "R1 发布受理" "$(echo "$PUB" | jq -r '.data.status')" "deploying"
assert_eq "R1.1 envJson 为空" "$(echo "$PUB" | jq -r '.data.envJson')" "{}"
if wait_status "$S1" running 300; then ok "R2 无 env 发布 → running（平台默认值生效）"; else bad "R2 未到 running"; fi
EFROM=$(kubectl -n $NS get deploy $K1 -o jsonpath='{range .spec.template.spec.containers[0].envFrom[*]}{.configMapRef.name}{.secretRef.name}{"\n"}{end}')
assert_eq "R3 envFrom 四源顺序" "$(echo "$EFROM" | tr '\n' '/')" \
  "oaf-platform-default-config/oaf-platform-default-secret/$K1-env/$K1-env-secret/"
POD_SENSE=$(kubectl -n $NS exec deploy/$K1 -- sh -c 'env' 2>/dev/null | grep -cE '^LLM_API_KEY=.+|^AGENT_REDIS_URL=.+|^CHECKPOINT_PASSWORD=.+')
assert_eq "R4 Pod 内敏感键到达（存在性，值不回显）" "$POD_SENSE" "3"
assert_eq "R5 服务 CM 无键（无敏感泄漏）" "$(kubectl -n $NS get cm $K1-env -o json | jq -r '.data | length')" "0"
k8s_res secret $K1-env-secret && ok "R6 服务 Secret 存在（空对象）" || bad "R6 服务 Secret 缺失"

say "场景 S：发布携带敏感键 → 路由服务 Secret"
SECRET_VAL="sk-route-e2e-$(date +%s)"
PUB2=$(api POST /services "{\"packageId\":$PKG_ID,\"name\":\"cfgsecret-e2e-b\",\"env\":{\"LLM_API_KEY\":\"$SECRET_VAL\",\"LOG_LEVEL\":\"info\"}}")
S2=$(echo "$PUB2" | jq -r '.data.id'); K2=$(echo "$PUB2" | jq -r '.data.k8sName')
assert_eq "S1 envJson 剔除敏感键" "$(echo "$PUB2" | jq -r '.data.envJson')" '{"LOG_LEVEL":"info"}'
if wait_status "$S2" running 300; then ok "S2 服务 running"; else bad "S2 未到 running"; fi
assert_eq "S3 路由键进服务 Secret" "$(kubectl -n $NS get secret $K2-env-secret -o json | jq -r '.data.LLM_API_KEY | @base64d')" "$SECRET_VAL"
assert_eq "S4 非敏感键在服务 CM" "$(kubectl -n $NS get cm $K2-env -o jsonpath='{.data.LOG_LEVEL}')" "info"
DETAIL=$(api GET "/services/$S2")
assert_not_contains "S5 详情不泄漏明文" "$DETAIL" "$SECRET_VAL"
assert_contains "S6 envSecretKeys 掩码视图" "$DETAIL" '"key":"LLM_API_KEY","hasValue":true'

say "场景 T：sticky 三态 + 空串删除回落平台默认"
PATCH=$(curl -s -X PATCH "$BASE/services/$S2/env" -H 'Content-Type: application/json' -d '{"env":{"LOG_LEVEL":"debug"}}')
assert_eq "T1 仅改非敏感键受理" "$(echo "$PATCH" | jq -r '.code')" "0"
wait_status "$S2" running 300 >/dev/null
assert_eq "T2 sticky：服务 Secret 敏感键保持" "$(kubectl -n $NS get secret $K2-env-secret -o json | jq -r '.data.LLM_API_KEY | @base64d')" "$SECRET_VAL"
PATCH2=$(curl -s -X PATCH "$BASE/services/$S2/env" -H 'Content-Type: application/json' -d '{"env":{"LOG_LEVEL":"debug","LLM_API_KEY":""}}')
assert_eq "T3 空串删除受理" "$(echo "$PATCH2" | jq -r '.code')" "0"
wait_status "$S2" running 300 >/dev/null
assert_eq "T4 删除后服务 Secret 不含该键" "$(kubectl -n $NS get secret $K2-env-secret -o json | jq -r '.data | has("LLM_API_KEY")')" "false"

say "场景 U：apply-restart 点名生效"
AR=$(api POST /platform-config/apply-restart "{\"serviceIds\":[$S2]}")
assert_eq "U1 点名重启受理" "$(echo "$AR" | jq -r '.code')" "0"
assert_eq "U2 restarted 恰为点名服务" "$(echo "$AR" | jq -r ".data.restarted | map(select(.id==$S2)) | length")" "1"
if wait_status "$S2" running 300; then ok "U3 重启后回 running"; else bad "U3 未回 running"; fi
assert_eq "U4 spec 重刷保持四源" "$(kubectl -n $NS get deploy $K2 -o jsonpath='{.spec.template.spec.containers[0].envFrom}' | jq 'length')" "4"

say "场景 V：删除零残留（含服务 Secret）"
api DELETE "/services/$S1" >/dev/null; api DELETE "/services/$S2" >/dev/null
sleep 3
LEFT=$(kubectl -n $NS get secret,cm,deploy,ingress -o name 2>/dev/null | grep -c "cfgsecret-e2e")
assert_eq "V1 集群零残留（含服务 Secret）" "$LEFT" "0"
k8s_res secret oaf-platform-default-secret && ok "V2 平台默认 Secret 保留" || bad "V2 平台 Secret 被误删"
k8s_res cm oaf-platform-default-config && ok "V3 平台默认 CM 保留" || bad "V3 平台 CM 被误删"

echo -e "\n\033[1;34m== 结果汇总 ==\033[0m"
echo "----------------------------------------"
echo "PASS: $PASS  FAIL: $FAIL"
[ $FAIL -gt 0 ] && { echo "failed: ${FAILED_CASES[*]}"; exit 1; }
exit 0

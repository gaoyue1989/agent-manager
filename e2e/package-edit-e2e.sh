#!/usr/bin/env bash
# OAF 包在线编辑 E2E：预览/下载/在线编辑生成新版本 → republish 服务切换新版本 → 引用计数迁移 → 清理
# 前置：platform-backend 已部署于 agent-platform，ingress :30080 可达，fixtures/demo-agent-v1.zip 存在
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

# 业务镜像与运行时 env（同 platform-e2e.sh 约定）
source "$(dirname "$0")/../.env.secrets" 2>/dev/null || source /root/agent-manager/.env.secrets
CKPT_URL="jdbc:mysql://oaf-mysql.$NS.svc.cluster.local:3306/oaf_checkpoint?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
export RUNTIME_ENV="{\"LLM_API_KEY\":\"$LLM_API_KEY\",\"LLM_MODEL_ID\":\"$LLM_MODEL\",\"LLM_BASE_URL\":\"$LLM_ENDPOINT\",\"CHECKPOINT_JDBC_URL\":\"$CKPT_URL\",\"CHECKPOINT_USERNAME\":\"oaf\",\"CHECKPOINT_PASSWORD\":\"OafPlatform2026\"}"

api() {
  local m=$1 p=$2 b=${3:-}
  if [ -n "$b" ]; then curl -s -X "$m" "$BASE$p" -H 'Content-Type: application/json' -d "$b"; else curl -s -X "$m" "$BASE$p"; fi
}
upload_zip() { curl -s -X POST "$BASE/packages" -F "file=@$1"; }

wait_status() {
  local id=$1 want=$2 timeout=${3:-240} waited=0 got=""
  while true; do
    got=$(api GET "/services/$id" | jq -r '.data.status')
    [ "$got" == "$want" ] && return 0
    [ $waited -ge $timeout ] && { echo "timeout waiting $want (last=$got)"; return 1; }
    sleep 5; waited=$((waited+5))
  done
}

PICK_IMG() {
  local pick
  pick=$(api GET /images | jq -r '[.data[].Image | select(. != null and contains(":5001"))][0] // .data[0].Image // empty')
  echo "${pick:-agent-framework:latest}"
}

# 本轮隔离基线：脚本开始前已存在的 e2e/demo 包数（V4.5 断言用增量）
BASE_DEMO=$(api GET "/packages?slug=e2e/demo" | jq -r '.data | length')

cleanup_ids=()
cleanup() {
  say "清理本轮资源"
  for sid in "${cleanup_ids[@]:-}"; do
    [ -n "$sid" ] && api DELETE "/services/$sid" >/dev/null
  done
  # set -u 下未赋值变量需 :- 防御，否则提前失败时 cleanup 自身报 unbound 中止、残留资源
  for pid in ${PKG1_ID:-} ${PKG2_ID:-}; do
    [ -n "$pid" ] && api DELETE "/packages/$pid" >/dev/null
  done
  return 0
}
trap cleanup EXIT

say "V0 准备：上传基础包"
UP=$(upload_zip "$FIXDIR/demo-agent-v1.zip")
PKG1_ID=$(echo "$UP" | jq -r '.data.id')
assert_eq "V0 上传基础包" "$(echo "$UP" | jq -r '.code')" "0"
[ -n "$PKG1_ID" ] && [ "$PKG1_ID" != "null" ] || { bad "V0 无 packageId，中止"; exit 1; }
cleanup_ids=()

say "V1 在线预览：单文件读取"
F=$(api GET "/packages/$PKG1_ID/files?path=AGENTS.md")
assert_contains "V1.1 预览含 frontmatter" "$F" 'vendorKey'
assert_eq "V1.2 文本判定 binary=false" "$(echo "$F" | jq -r '.data.binary')" "false"

FBIN=$(api GET "/packages/$PKG1_ID/files?path=../hack")
assert_eq "V1.3 路径穿越 → 非200" "$(echo "$FBIN" | jq -r '.code')" "400"

FMISS=$(api GET "/packages/$PKG1_ID/files?path=no/such.txt")
assert_eq "V1.4 文件不存在 → 非200" "$(echo "$FMISS" | jq -r '.code')" "404"

say "V2 整包下载与单文件下载"
HTTP_CODE=$(curl -s -o /tmp/pkg-dl.zip -w '%{http_code}' "$BASE/packages/$PKG1_ID/download")
assert_eq "V2.1 整包下载 200" "$HTTP_CODE" "200"
UNZIP_N=$(unzip -l /tmp/pkg-dl.zip 2>/dev/null | grep -c "AGENTS.md")
assert_eq "V2.2 下载 zip 含 AGENTS.md" "$UNZIP_N" "1"

HTTP_CODE=$(curl -s -o /tmp/file-dl.md -w '%{http_code}' "$BASE/packages/$PKG1_ID/files/download?path=AGENTS.md")
assert_eq "V2.3 单文件下载 200" "$HTTP_CODE" "200"
assert_contains "V2.4 单文件内容正确" "$(cat /tmp/file-dl.md)" "E2E Demo"

say "V3 在线编辑生成新版本"
# 新 frontmatter：version 1.1.0 + 正文改写；同时删除 README.md、新增 skills/echo/SKILL.md
NEW_AGENTS=$(unzip -p "$FIXDIR/demo-agent-v1.zip" AGENTS.md | sed 's/version: "1.0.0"/version: "1.1.0"/')
BODY=$(jq -n --arg md "$NEW_AGENTS" '{
  upserts: [
    {path: "AGENTS.md", content: $md},
    {path: "skills/echo/SKILL.md", content: "---\nname: echo\ndescription: echo skill v1.1\n---\n# Echo"}
  ],
  deletes: ["README.md"]
}')
CV=$(api POST "/packages/$PKG1_ID/versions" "$BODY")
assert_eq "V3.1 生成新版本" "$(echo "$CV" | jq -r '.code')" "0"
PKG2_ID=$(echo "$CV" | jq -r '.data.package.id')
NEW_VER=$(echo "$CV" | jq -r '.data.package.version')
SRC_ID=$(echo "$CV" | jq -r '.data.package.sourcePackageId')
assert_eq "V3.2 新版本号 1.1.0" "$NEW_VER" "1.1.0"
assert_eq "V3.3 派生自基础包" "$SRC_ID" "$PKG1_ID"
assert_eq "V3.4 基础包未被修改" "$(api GET "/packages/$PKG1_ID/files?path=AGENTS.md" | jq -r '.data.content' | grep -c 'version: "1.1.0"')" "0"
assert_eq "V3.5 新增文件可读" "$(api GET "/packages/$PKG2_ID/files?path=skills/echo/SKILL.md" | jq -r '.data.content' | grep -c 'echo skill v1.1')" "1"
assert_eq "V3.6 删除文件生效" "$(api GET "/packages/$PKG2_ID/files?path=README.md" | jq -r '.code')" "404"
assert_eq "V3.7 基础包 README 仍在" "$(api GET "/packages/$PKG1_ID/files?path=README.md" | jq -r '.data.binary')" "false"

say "V4 异常路径"
# 无有效变更
NC=$(api POST "/packages/$PKG1_ID/versions" '{"deletes":["not-exist.txt"]}')
assert_eq "V4.1 无有效变更 → 400" "$(echo "$NC" | jq -r '.code')" "400"
# 删除根级 AGENTS.md
DELMD=$(api POST "/packages/$PKG1_ID/versions" '{"deletes":["AGENTS.md"],"upserts":[{"path":"x.txt","content":"x"}]}')
assert_eq "V4.2 删除 AGENTS.md → 400" "$(echo "$DELMD" | jq -r '.code')" "400"
# 乐观锁冲突
LOCK=$(api POST "/packages/$PKG1_ID/versions" '{"upserts":[{"path":"x.txt","content":"x"}],"expectedBaseChecksum":"deadbeef"}')
assert_eq "V4.3 checksum 不符 → 409" "$(echo "$LOCK" | jq -r '.code')" "409"
# 非法 frontmatter
BADMD=$(api POST "/packages/$PKG1_ID/versions" '{"upserts":[{"path":"AGENTS.md","content":"no frontmatter"}]}')
assert_eq "V4.4 非法 frontmatter → 400" "$(echo "$BADMD" | jq -r '.code')" "400"
# slug 过滤：基础 1 + 新版本 1（加上运行前基线）
SLUGF=$(api GET "/packages?slug=e2e/demo")
assert_eq "V4.5 slug 过滤含两版本" "$(echo "$SLUGF" | jq -r '.data | length')" "$((BASE_DEMO + 2))"

say "V5 发布服务 → 切换新版本包（republish）"
PUB=$(api POST /services "{\"packageId\":$PKG1_ID,\"image\":\"$(PICK_IMG)\",\"env\":$(python3 -c "import json,os;print(json.dumps(json.loads(os.environ['RUNTIME_ENV'])))")}")
SVC_ID=$(echo "$PUB" | jq -r '.data.id')
K8S_NAME=$(echo "$PUB" | jq -r '.data.k8sName')
assert_eq "V5.1 发布受理" "$(echo "$PUB" | jq -r '.data.status')" "deploying"
cleanup_ids+=("$SVC_ID")
if wait_status "$SVC_ID" running 300; then ok "V5.2 基础包发布 running"; else bad "V5.2 未到 running"; fi
assert_eq "V5.3 注册版本 1.0.0" "$(api GET "/services/$SVC_ID" | jq -r '.data.registeredVersion')" "1.0.0"

RE=$(api POST "/services/$SVC_ID/republish" "{\"packageId\":$PKG2_ID}")
assert_eq "V5.4 切换新版本受理" "$(echo "$RE" | jq -r '.data.status')" "deploying"
SUBPATH=$(kubectl -n $NS get deploy $K8S_NAME -o jsonpath='{.spec.template.spec.containers[0].volumeMounts[0].subPath}')
assert_eq "V5.5 subPath 切到新包目录" "$SUBPATH" "packages/$PKG2_ID"
RC1=$(api GET "/packages/$PKG1_ID" | jq -r '.data.package.refCount')
RC2=$(api GET "/packages/$PKG2_ID" | jq -r '.data.package.refCount')
assert_eq "V5.6 引用计数迁移" "${RC1}->${RC2}" "0->1"
if wait_status "$SVC_ID" running 300; then ok "V5.7 新版本 running"; else bad "V5.7 未回 running"; fi
assert_eq "V5.8 注册版本升级 1.1.0" "$(api GET "/services/$SVC_ID" | jq -r '.data.registeredVersion')" "1.1.0"

say "V6 服务列表按包过滤"
SVCS=$(api GET "/services?packageId=$PKG2_ID")
assert_eq "V6.1 按包过滤命中" "$(echo "$SVCS" | jq -r '.data | length')" "1"
SVCS0=$(api GET "/services?packageId=$PKG1_ID")
assert_eq "V6.2 旧包 0 引用" "$(echo "$SVCS0" | jq -r '.data | length')" "0"

say "V7 清理与零残留"
api DELETE "/services/$SVC_ID" >/dev/null
sleep 2
W=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/services/$SVC_ID")
assert_eq "V7.1 服务已删" "$W" "404"
api DELETE "/packages/$PKG1_ID" >/dev/null
api DELETE "/packages/$PKG2_ID" >/dev/null
sleep 1
W1=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/packages/$PKG1_ID")
W2=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/packages/$PKG2_ID")
assert_eq "V7.2 两版本包均删除" "${W1}/${W2}" "404/404"
LEFT=$(kubectl -n $NS exec deployment/platform-backend -- sh -c "ls /data/packages 2>/dev/null | grep -c '^$PKG1_ID$\|^$PKG2_ID$'")
assert_eq "V7.3 PVC 目录已清理" "$LEFT" "0"

say "结果汇总"
echo "----------------------------------------"
echo "PASS: $PASS  FAIL: $FAIL"
if [ $FAIL -gt 0 ]; then printf 'failed: %s\n' "${FAILED_CASES[@]}"; exit 1; fi
exit 0

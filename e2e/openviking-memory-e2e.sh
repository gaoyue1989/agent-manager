#!/usr/bin/env bash
# OpenViking 记忆供给 E2E（openviking-memory-integration-design-261116 §11.2）：
# 发布自动建户/env 注入 → 包内知识导入 → 双轮对话记忆闭环（保存→提取→检索注入）→
# per-user 隔离 → 删除回收 account。
#
# 前置（真实环境）：
#   1. OpenViking 服务可达（宿主 docker 或集群内 openviking-svc），root key 在手
#   2. platform-backend 已部署且 OPENVIKING_ENABLED=true（manifests/platform.yaml）
#   3. 业务镜像 ≥ openviking-poc（含 VikingMemoryClient 链路）
#   4. .env.secrets 含 LLM_*（对话模型凭据）
# 用法：BASE=http://localhost:30080/api/v1 OV_BASE=http://127.0.0.1:1933 \
#       OV_ROOT_KEY=<root-key> bash e2e/openviking-memory-e2e.sh
set -u
BASE="${BASE:-http://localhost:30080/api/v1}"
NS=agent-platform
OV_BASE="${OV_BASE:-http://127.0.0.1:1933}"
OV_ROOT_KEY="${OV_ROOT_KEY:?OV_ROOT_KEY is required (OpenViking root api key)}"
OV_ADMIN_USER="svc-system"
PASS=0; FAIL=0
declare -a FAILED_CASES

say()  { echo -e "\n\033[1;34m== $* ==\033[0m"; }
ok()   { PASS=$((PASS+1)); echo "  \033[32mPASS\033[0m $1"; }
bad()  { FAIL=$((FAIL+1)); FAILED_CASES+=("$1"); echo "  \033[31mFAIL\033[0m $1"; }

source "$(dirname "$0")/../.env.secrets" 2>/dev/null || source /root/agent-manager/.env.secrets
ADMIN_PASSWORD="${ADMIN_PASSWORD:-OafConsole2026}"  # platform.yaml ADMIN_INIT_PASSWORD(运维种子)
CKPT_URL="jdbc:mysql://oaf-mysql.$NS.svc.cluster.local:3306/oaf_checkpoint?useSSL=false&allowPublicKeyRetrieval=true&serverSideTimezone=UTC&serverTimezone=UTC"
RUNTIME_ENV=$(python3 -c "import json;print(json.dumps({
  'LLM_API_KEY':'$LLM_API_KEY','LLM_MODEL_ID':'$LLM_MODEL','LLM_BASE_URL':'$LLM_ENDPOINT',
  'AGENT_REDIS_URL':'redis://oaf-redis.$NS.svc.cluster.local:6379',
  'SANDBOX_ENABLED':'false',
  'CHECKPOINT_JDBC_URL':'$CKPT_URL',
  'CHECKPOINT_USERNAME':'oaf','CHECKPOINT_PASSWORD':'OafPlatform2026'}))")

api() { curl -s -X "$1" "$BASE$2" -H 'Content-Type: application/json' ${3:+-d "$3"}; }
JWT=$(curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$ADMIN_PASSWORD\"}" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['token'])" 2>/dev/null)
api_admin() { curl -s -X "$1" "$BASE$2" -H 'Content-Type: application/json' -H "Authorization: Bearer $JWT" ${3:+-d "$3"}; }
ov()  { curl -s -X "$1" "$OV_BASE$2" -H 'Content-Type: application/json' -H "X-API-Key: $OV_ROOT_KEY" ${3:+-d "$3"}; }
IMG="${IMG:-172.20.0.1:5001/agent-framework:openviking-poc}"

say "P0 前置检查"
H=$(curl -s -m 5 "$OV_BASE/health")
case "$H" in *'"healthy":true'*) ok "OpenViking healthy ($H)";; *) bad "OpenViking /health: $H"; exit 1;; esac
declare -A ACCOUNTS_BEFORE
for id in $(ov GET /api/v1/admin/accounts | python3 -c "import json,sys;print(' '.join(a['account_id'] for a in json.load(sys.stdin).get('result') or []))" 2>/dev/null); do
  ACCOUNTS_BEFORE[$id]=1
done
ok "baseline accounts: ${#ACCOUNTS_BEFORE[@]}"

say "P1 上传带 knowledge 的测试包"
TMP=$(mktemp -d)
RUN_ID=$(date +%s)
mkdir -p "$TMP/viking-demo/knowledge"
cat > "$TMP/viking-demo/AGENTS.md" <<MD
---
name: VikingMemDemo
vendorKey: acme
agentKey: vikingdemo
version: "1.0.${RUN_ID}"
slug: acme/viking-demo-${RUN_ID}
description: OpenViking memory e2e demo agent
author: "@acme"
license: MIT
---
You are a helpful assistant. Answer concisely in Chinese.
MD
echo "# 发布指南
平台发布流程分为：上传 OAF 包、配置发布参数、确认发布三步。" > "$TMP/viking-demo/knowledge/release-guide.md"
(cd "$TMP/viking-demo" && zip -qr "$TMP/viking-demo.zip" .)
UP=$(curl -s -X POST "$BASE/packages" -F "file=@$TMP/viking-demo.zip")
PKG_ID=$(echo "$UP" | jq -r '.data.id')
[ -n "$PKG_ID" ] && [ "$PKG_ID" != "null" ] && ok "package uploaded id=$PKG_ID" || { bad "upload: $UP"; exit 1; }

say "P2 发布（平台启用 OpenViking 即默认接管 + 知识导入）"
PUB=$(api POST /services "{\"packageId\":$PKG_ID,\"image\":\"$IMG\",\"name\":\"viking-mem-demo\",\"env\":$(echo "$RUNTIME_ENV" | python3 -c "import json,sys;d=json.load(sys.stdin);d['OVK_KNOWLEDGE_IMPORT']='true';print(json.dumps(d))")}")
SVC_ID=$(echo "$PUB" | jq -r '.data.id')
[ -n "$SVC_ID" ] && [ "$SVC_ID" != "null" ] && ok "publish accepted id=$SVC_ID" || { bad "publish: $PUB"; exit 1; }
K8S_NAME=$(api GET "/services/$SVC_ID" | jq -r '.data.k8sName')
SHORT=$(api GET "/services/$SVC_ID" | jq -r '.data.shortName')
ok "k8sName=$K8S_NAME short=$SHORT"

say "P3 OpenViking 自动建户断言（发布同步动作）"
ACC_EXIST=$(ov GET /api/v1/admin/accounts | python3 -c "import json,sys;print(any(a['account_id']=='$K8S_NAME' for a in (json.load(sys.stdin).get('result') or [])))")
[ "$ACC_EXIST" == "True" ] && ok "account $K8S_NAME created" || bad "account $K8S_NAME missing"

say "P4 知识导入断言（OVK_KNOWLEDGE_IMPORT=true）"
sleep 3
# 服务身份 ADMIN key 从服务 Secret 读（轮换端点会作废业务面已注入的 key，严禁脚本触发）
ADMIN_KEY=$(kubectl get secret "$K8S_NAME-env-secret" -n $NS -o jsonpath='{.data.OVK_ADMIN_KEY}' | base64 -d)
[ -n "$ADMIN_KEY" ] && ok "admin key read from service secret (${ADMIN_KEY%%.*}.…)" || { bad "secret read"; exit 1; }
KNOW=$(curl -s "$OV_BASE/api/v1/fs/ls?uri=viking%3A%2F%2Fresources%2Facme" -H "X-API-Key: $ADMIN_KEY")
case "$KNOW" in *viking-demo*) ok "knowledge imported: viking://resources/acme/<slug>/ found";; *) bad "resources/acme ls: $(echo $KNOW | head -c 200)";; esac

say "P5 服务就绪（register 终态即业务 Pod 可对话）"
wait_terminal() {
  local id=$1 timeout=${2:-240} waited=0 got=""
  while true; do
    got=$(api GET "/services/$id" | jq -r '.data.status')
    case "$got" in running|deploy_failed|register_failed|error) echo "$got"; return 0;; esac
    [ $waited -ge $timeout ] && { echo "$got"; return 1; }
    sleep 5; waited=$((waited+5))
  done
}
STATUS=$(wait_terminal "$SVC_ID")
case "$STATUS" in running|register_failed) ok "service terminal: $STATUS";; *) bad "service status: $STATUS"; exit 1;; esac

AGENT_BASE="http://localhost:30080/agent/$SHORT"
chat() { # chat <sid> <user> <message> -> 输出存 /tmp/ov-e2e-<sid>.sse,回显全文
  curl -s --max-time 240 -X POST "$AGENT_BASE/threads/chat" -H 'Content-Type: application/json' \
    -d "$(python3 -c "import json,sys;print(json.dumps({'sessionId':sys.argv[1],'message':sys.argv[2],'userId':sys.argv[3]}))" "$1" "$3" "$2")" \
    | tee "/tmp/ov-e2e-$1.sse"
}

say "P6 双轮记忆闭环（user=viking-e2e）"
U="viking-e2e-$(date +%s)"
S1="vm1-$U"
chat "$S1" "$U" "请记住:我最喜欢的编程语言是 Rust。回复'已记住'即可。" > /dev/null
case "$(cat "/tmp/ov-e2e-$S1.sse")" in
  *AGENT_END*) ok "turn1 saved preference (AGENT_END reached)";;
  *) bad "turn1 terminal: $(tail -c 200 "/tmp/ov-e2e-$S1.sse")";;
esac

say "  等待后台记忆提取（~40s，VLM mimo 实测 30s）"
sleep 45
USER_SEED=$(kubectl get secret "$K8S_NAME-env-secret" -n $NS -o jsonpath='{.data.OVK_USER_SEED}' | base64 -d)
UKEY=$(K8S_NAME="$K8S_NAME" E2E_U="$U" SEED="$USER_SEED" python3 -c '
import base64, hashlib, os
b = lambda x: base64.urlsafe_b64encode(x).rstrip(b"=").decode()
acct, u, seed = os.environ["K8S_NAME"], os.environ["E2E_U"], os.environ["SEED"]
d = hashlib.sha256((u + chr(0) + seed).encode()).hexdigest().encode()
print(b(acct.encode()) + "." + b(u.encode()) + "." + b(d))')
MEM=$(curl -s "$OV_BASE/api/v1/fs/ls?uri=viking%3A%2F%2F~%2Fmemories" -H "X-API-Key: $UKEY")
case "$MEM" in *entities*|*preferences*|*.md*) ok "memories landed for $U";; *) bad "memories ls: $(echo $MEM | head -c 200)";; esac

S2="vm2-$U"
chat "$S2" "$U" "我最喜欢什么编程语言?只回答语言名,不要解释。" > /dev/null
R2=$(grep -o '"delta":"[^"]*"' "/tmp/ov-e2e-$S2.sse" | sed 's/"delta":"//;s/"$//' | tr -d '\n')
case "$R2" in *Rust*|*rust*) ok "turn2 recalled preference (reply: $R2)";; *) bad "turn2 no Rust (reply: ${R2:-empty}): $(tail -c 200 "/tmp/ov-e2e-$S2.sse")";; esac

say "P7 per-user 隔离（另一用户同服务问同问题）"
U2="viking-other-$RANDOM"
S3="vm3-$U2"
chat "$S3" "$U2" "我最喜欢什么编程语言?只回答语言名。" > /dev/null
R3=$(grep -o '"delta":"[^"]*"' "/tmp/ov-e2e-$S3.sse" | sed 's/"delta":"//;s/"$//' | tr -d '\n')
case "$R3" in *Rust*|*rust*) bad "isolation leak: other user saw Rust";; *) ok "isolation holds (no cross-user recall)";; esac

say "P8 per-user key 签发（admin API）"
KEY=$(api_admin POST "/admin/services/$SVC_ID/memory-keys" "{\"userId\":\"studio-$U\"}" | tee /dev/stderr | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['userKey'])" 2>/dev/null)
case "$KEY" in *.*.*) ok "per-user key issued (${KEY%%.*}.…)";; *) bad "memory-keys: $(echo $KEY | head -c 120)";; esac

say "P9 删除服务 → account 回收"
api DELETE "/services/$SVC_ID" >/dev/null
sleep 5
ACC_AFTER=$(ov GET /api/v1/admin/accounts | python3 -c "import json,sys;print(any(a['account_id']=='$K8S_NAME' for a in (json.load(sys.stdin).get('result') or [])))")
[ "$ACC_AFTER" == "False" ] && ok "account deleted with service" || bad "account $K8S_NAME still exists"

say "结果: PASS=$PASS FAIL=$FAIL"
[ $FAIL -gt 0 ] && printf '  failed: %s\n' "${FAILED_CASES[@]}"
exit $([ $FAIL -eq 0 ] && echo 0 || echo 1)

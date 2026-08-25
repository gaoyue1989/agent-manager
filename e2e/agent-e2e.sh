#!/usr/bin/env bash
# E2E 场景 E：经 release-agent 自然语言(A2A)驱动第三方服务发布/删除
# 前置：release-agent 已由平台自举发布且 running
set -u
BASE="${BASE:-http://localhost:30080/api/v1}"
AGENT_URL="${AGENT_URL:-http://172.20.0.3:30080/agent/release-agent/}"
FIXDIR="$(cd "$(dirname "$0")/fixtures" && pwd)"
NS=agent-platform
PASS=0; FAIL=0; declare -a FAILED_CASES
say() { echo -e "\n\033[1;34m== $* ==\033[0m"; }
ok()  { PASS=$((PASS+1)); echo "  \033[32mPASS\033[0m $1"; }
bad() { FAIL=$((FAIL+1)); FAILED_CASES+=("$1"); echo "  \033[31mFAIL\033[0m $1"; }

source "$(dirname "$0")/../.env.secrets" 2>/dev/null || source /root/agent-manager/.env.secrets
RUN_ID="$(date +%s)"
E2E_USER="e2e-$RUN_ID"

# a2a_send <text> → 输出 agent 回复全文
a2a_send() {
  local text="$1"
  local payload
  payload=$(python3 -c "
import json,sys
print(json.dumps({'jsonrpc':'2.0','id':1,'method':'message/send','params':{
 'message':{'role':'user','parts':[{'kind':'text','text':sys.argv[1]}],'kind':'message','messageId':'e2e-'+str(int(__import__('time').time())),'metadata':{'userId':sys.argv[2]}},
 }}))" "$text" "$E2E_USER")
  local resp
  resp=$(curl -s --max-time 600 -X POST "$AGENT_URL" -H 'Content-Type: application/json' -d "$payload")
  if [ -z "$resp" ]; then echo "<empty-response>"; return; fi
  echo "$resp" | python3 -c "
import json,sys
try:
    r=json.load(sys.stdin)
except Exception:
    print('<non-json-response>'); sys.exit(0)
def texts(o,acc):
    if isinstance(o,dict):
        for v in o.values(): texts(v,acc)
    elif isinstance(o,list):
        for v in o: texts(v,acc)
    elif isinstance(o,str): acc.append(o)
acc=[]; texts(r.get('result',r),acc)
print(' '.join(acc))"
}

say "E-前置：上传待发布的第三方配置包"
PKG_ID=$(curl -s -X POST "$BASE/packages" -F "file=@$FIXDIR/demo-agent-v2.zip" | jq -r '.data.id')
echo "  third-party packageId=$PKG_ID"
[ -n "$PKG_ID" ] && [ "$PKG_ID" != "null" ] && ok "E0 上传成功" || bad "E0 上传失败"

SVC_NAME="e2e-agent-published"
CKPT_URL="jdbc:mysql://oaf-mysql.$NS.svc.cluster.local:3306/oaf_checkpoint?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"

say "E-阶段1：自然语言指令 → 发布服务"
REPLY=$(a2a_send "请把 packageId=$PKG_ID 的配置包发布成服务，名字用 $SVC_NAME，镜像用 agent-framework:latest。环境变量如下：
LLM_API_KEY=$LLM_API_KEY
LLM_MODEL_ID=$LLM_MODEL
LLM_BASE_URL=$LLM_ENDPOINT
CHECKPOINT_JDBC_URL=$CKPT_URL
CHECKPOINT_USERNAME=oaf
CHECKPOINT_PASSWORD=OafPlatform2026
完成后请报告最终状态与 endpoint。")
echo "  agent 回复摘要: $(echo "$REPLY" | head -c 200)"
echo "$REPLY" | grep -qi "running\|已发布\|发布成功" && ok "E1 回复包含发布成功语义" || bad "E1 回复未见成功语义"

say "E-阶段2：验证服务真实存在并 running"
FOUND=""
for i in $(seq 1 30); do
  ST=$(curl -s "$BASE/services?keyword=$SVC_NAME" | jq -r '.data[0].status // empty')
  if [ "$ST" == "running" ]; then FOUND=1; break; fi
  sleep 10
done
if [ -n "$FOUND" ]; then
  ok "E2 第三方服务达到 running"
else
  bad "E2 第三方服务未达 running (last=$ST)"
fi
REG=""
for i in $(seq 1 12); do
  REG=$(curl -s "$BASE/services?keyword=$SVC_NAME" | jq -r '.data[0].registeredName // empty')
  [ "$REG" == "E2E Demo Agent" ] && break
  sleep 5
done
[ "$REG" == "E2E Demo Agent" ] && ok "E3 A2A 注册信息正确 ($REG)" || bad "E3 注册信息异常 ($REG)"

say "E-阶段3：自然语言指令 → 删除服务（应触发确认）"
REPLY2=$(a2a_send "请删除服务 $SVC_NAME")
echo "  agent 回复摘要: $(echo "$REPLY2" | head -c 200)"
STILL=$(curl -s "$BASE/services?keyword=$SVC_NAME" | jq -r '.data[0].status // empty')
ASKED=$(echo "$REPLY2" | grep -qi "确认\|confirm\|删除.*吗\|是否" && echo 1)
if { [ -n "$STILL" ] && [ "$STILL" != "null" ]; } || [ -n "$ASKED" ]; then
  ok "E4 删除前存在确认语义（服务仍在 或 回复要求确认）"
else
  bad "E4 无确认直接删除"
fi

say "E-阶段4：明确确认后执行删除"
K8S_NAME=$(curl -s "$BASE/services?keyword=$SVC_NAME" | jq -r '.data[0].k8sName // empty')
REPLY3=$(a2a_send "确认删除 $SVC_NAME（k8sName=$K8S_NAME），请按规范传入 confirm_k8s_name 立即执行")
echo "  agent 回复摘要: $(echo "$REPLY3" | head -c 200)"
DELETED=""
for i in $(seq 1 20); do
  STILL=$(curl -s "$BASE/services?keyword=$SVC_NAME" | jq -r '.data[0].status // empty')
  if [ -z "$STILL" ] || [ "$STILL" == "null" ]; then DELETED=1; break; fi
  sleep 10
done
[ -n "$DELETED" ] && ok "E5 确认后服务已被删除" || bad "E5 确认后仍未删除 (last=$STILL)"

# 清理测试包
curl -s -X DELETE "$BASE/packages/$PKG_ID" >/dev/null

say "结果汇总"
echo "----------------------------------------"
echo "PASS: $PASS  FAIL: $FAIL"
if [ $FAIL -gt 0 ]; then printf 'failed: %s\n' "${FAILED_CASES[@]}"; exit 1; fi
exit 0

#!/usr/bin/env bash
# E2E 场景 E：经 release-agent 自然语言驱动第三方服务发布/删除（HITL 确认流全链路）
#
# 通道说明（2026-09-18 调整）：
#   变更类操作走 /threads/chat + /threads/{sid}/confirm-stream —— 平台一手 HITL 契约
#   （与 webui 确认卡同链路）。A2A (message/send) 通道自 9f3fff5 HITL 改造后，
#   ask 工具挂起态只存在于 harness checkpoint，不落平台 confirm_context，
#   无法经 confirm-stream 批准，故 A2A 仅保留查询类探针（E7）。
# 前置：release-agent 已由平台自举发布且 running（≥ 2026-09-17 HITL 版）
set -u
BASE="${BASE:-http://localhost:30080/api/v1}"
AGENT_URL="${AGENT_URL:-http://localhost:30080/agent/release-agent}"
FIXDIR="$(cd "$(dirname "$0")/fixtures" && pwd)"
NS=agent-platform
PASS=0; FAIL=0; declare -a FAILED_CASES
say() { echo -e "\n\033[1;34m== $* ==\033[0m"; }
ok()  { PASS=$((PASS+1)); echo "  \033[32mPASS\033[0m $1"; }
bad() { FAIL=$((FAIL+1)); FAILED_CASES+=("$1"); echo "  \033[31mFAIL\033[0m $1"; }

source "$(dirname "$0")/../.env.secrets" 2>/dev/null || source /root/agent-manager/.env.secrets
RUN_ID="$(date +%s)"
E2E_USER="e2e-$RUN_ID"
SID="e2e-$RUN_ID"
AGENT_BASE="${AGENT_URL%/}"

# chat <message> → 输出 SSE 原始流（turn 在 permission_ask 处或 AGENT_END 结束）
chat() {
  curl -s --max-time 600 -X POST "$AGENT_BASE/threads/chat" -H 'Content-Type: application/json' \
    -d "$(python3 -c "import json,sys;print(json.dumps({'sessionId':sys.argv[1],'message':sys.argv[2],'userId':sys.argv[3]}))" "$SID" "$1" "$E2E_USER")"
}

# confirm <tool_call_id> → 批准并续流（turn 正常结束输出完整 SSE）
confirm() {
  curl -sN --max-time 600 -X POST "$AGENT_BASE/threads/$SID/confirm-stream" \
    -H 'Content-Type: application/json' \
    -d "{\"results\":[{\"tool_call_id\":\"$1\",\"confirmed\":true}]}"
}

# pending_call <tool> → 当前会话待确认卡片中该工具的 tool_call_id（无则空）
pending_call() {
  curl -s "$AGENT_BASE/threads/$SID/history" | jq -r --arg t "$1" \
    '[.pendingConfirm.tools[]? | select(.name == $t) | .tool_call_id][0] // empty'
}

# a2a_send <text> → A2A message/send 回复全文（E7 查询探针用）
a2a_send() {
  local text="$1"
  local payload
  payload=$(python3 -c "
import json,sys
print(json.dumps({'jsonrpc':'2.0','id':1,'method':'message/send','params':{
 'message':{'role':'user','parts':[{'kind':'text','text':sys.argv[1]}],'kind':'message','messageId':'e2e-'+str(int(__import__('time').time())),'metadata':{'userId':sys.argv[2]}},
 }}))" "$text" "$E2E_USER")
  local resp
  resp=$(curl -s --max-time 600 -X POST "$AGENT_BASE/" -H 'Content-Type: application/json' -d "$payload")
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
# 镜像用 GET /images 的全名（后端校验全名；短名会诱发 LLM 澄清追问而不调用工具）
RUNTIME_IMAGE=$(curl -s "$BASE/images" | jq -r '[.data[].Image | select(. != null and contains(":5001"))][0] // .data[0].Image // empty')

say "E-阶段1：自然语言指令 → 发布服务（HITL：publish_service ask 应挂起出卡）"
# 预热（workaround）：首回合触发沙箱创建并绑定到会话，避免恢复 turn 撞
# "No active sandbox — sandbox filesystem used outside of a call context"
# （恢复 turn 崩溃会让 checkpoint 里 ASKING 态残留，会话被锁死）
# 对应 harness 沙箱生命周期竞态（docs/release-agent-mcp-app-hitl-design-acceptance.md §18），
# 运行时修复落地后应移除；SANDBOX_ENABLED=false（应用默认）部署不受影响
chat "你好，请直接回复 ready。" > /dev/null
sleep 5
STREAM=$(chat "请把 packageId=$PKG_ID 的配置包发布成服务，名字用 $SVC_NAME，镜像用 $RUNTIME_IMAGE。环境变量如下：
LLM_API_KEY=$LLM_API_KEY
LLM_MODEL_ID=$LLM_MODEL
LLM_BASE_URL=$LLM_ENDPOINT
CHECKPOINT_JDBC_URL=$CKPT_URL
CHECKPOINT_USERNAME=oaf
CHECKPOINT_PASSWORD=OafPlatform2026
完成后请报告最终状态与 endpoint。")
echo "  流摘要: $(echo "$STREAM" | grep -o '"type":"[a-z_]*"' | sort | uniq -c | tr '\n' ' ')"
PUB_CALL=$(pending_call publish_service)
[ -n "$PUB_CALL" ] && ok "E1 发布请求挂起并出现确认卡 (tool_call_id=$PUB_CALL)" || bad "E1 未见 publish_service 确认卡（HITL 未生效？）"

say "E-阶段2：确认流批准发布 → 服务 running"
if [ -n "$PUB_CALL" ]; then
  CONF=$(confirm "$PUB_CALL")
  echo "  confirm-stream 摘要: $(echo "$CONF" | grep -o '"type":"[a-z_]*"' | sort | uniq -c | tr '\n' ' ')"
else
  echo "  （跳过：无挂起卡片可批准）"
fi
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

say "E-阶段3：自然语言指令 → 删除服务（HITL：ask 工具挂起等待确认卡）"
STREAM2=$(chat "请删除服务 $SVC_NAME")
echo "  流摘要: $(echo "$STREAM2" | grep -o '"type":"[a-z_]*"' | sort | uniq -c | tr '\n' ' ')"
STILL=$(curl -s "$BASE/services?keyword=$SVC_NAME" | jq -r '.data[0].status // empty')
if [ -n "$STILL" ] && [ "$STILL" != "null" ]; then
  ok "E4 HITL 挂起：服务未被直接删除 (status=$STILL)"
else
  bad "E4 无确认直接删除"
fi

say "E-阶段4：确认流批准删除 → 服务消失"
DEL_CALL=$(pending_call delete_service)
if [ -z "$DEL_CALL" ]; then
  bad "E5 未检测到待确认 delete_service 卡片（检查 release-agent 是否为 HITL 版部署）"
else
  ok "E5 检测到待确认卡片 (tool_call_id=$DEL_CALL)"
  CONF=$(confirm "$DEL_CALL")
  echo "  confirm-stream 摘要: $(echo "$CONF" | grep -o '"type":"[a-z_]*"' | sort | uniq -c | tr '\n' ' ')"
  DELETED=""
  for i in $(seq 1 20); do
    STILL=$(curl -s "$BASE/services?keyword=$SVC_NAME" | jq -r '.data[0].status // empty')
    if [ -z "$STILL" ] || [ "$STILL" == "null" ]; then DELETED=1; break; fi
    sleep 10
  done
  [ -n "$DELETED" ] && ok "E6 批准后服务已被删除" || bad "E6 批准后服务仍未删除 (last=$STILL)"
fi

say "E-阶段5：A2A 通道查询类探针（变更类见脚本头部说明）"
REPLY=$(a2a_send "请用一句话介绍你自己能做什么。")
echo "  agent 回复摘要: $(echo "$REPLY" | head -c 120)"
case "$REPLY" in
  "<empty-response>"|"<non-json-response>") bad "E7 A2A 查询无有效回复";;
  *) ok "E7 A2A 查询类消息可用";;
esac

# 兜底清理：失败路径不遗留测试服务/测试包
if [ -n "$STILL" ] && [ "$STILL" != "null" ]; then
  SVC_ID=$(curl -s "$BASE/services?keyword=$SVC_NAME" | jq -r '.data[0].id // empty')
  [ -n "$SVC_ID" ] && curl -s -X DELETE "$BASE/services/$SVC_ID" >/dev/null
fi
# 清理测试包
curl -s -X DELETE "$BASE/packages/$PKG_ID" >/dev/null

say "结果汇总"
echo "----------------------------------------"
echo "PASS: $PASS  FAIL: $FAIL"
if [ $FAIL -gt 0 ]; then printf 'failed: %s\n' "${FAILED_CASES[@]}"; exit 1; fi
exit 0

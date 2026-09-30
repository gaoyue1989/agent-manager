#!/usr/bin/env bash
# order-fulfillment demo 一键部署（经平台 API 实际发布）
# 前置：kind 集群 + platform-backend 存活；宿主机 nginx :8911 入口可用；
#       镜像 172.20.0.1:5001/agent-framework:latest 已推送（M1+修复版本）。
# 用法：./deploy.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
API="${API:-http://127.0.0.1:8911/api/v1}"
NS="agent-platform"
IMAGE="${IMAGE:-172.20.0.1:5001/agent-framework:latest}"
TOKEN="${DEMO_TOKEN:-of-demo-$(date +%s)}"

echo "== 1. 部署 biz-mcp（业务 mock MCP）=="
python3 - "$HERE/mcp/biz_mcp.py" "$HERE/k8s/biz-mcp.yaml.tmpl" > "$HERE/k8s/biz-mcp.yaml" <<'PY'
import sys
script = open(sys.argv[1], encoding='utf-8').read()
tmpl = open(sys.argv[2], encoding='utf-8').read()
indented = "\n".join(("    " + line) if line else "" for line in script.splitlines())
sys.stdout.write(tmpl.replace("{{ indent 4 .Script }}", indented))
PY
kubectl apply -f "$HERE/k8s/biz-mcp.yaml"
kubectl -n "$NS" rollout status deployment/biz-mcp --timeout=120s

echo "== 2. 打包并上传五个 OAF 包 =="
upload() { # $1=package dir → 输出 packageId
  local dir="$1" zip
  zip="$(mktemp --suffix=.zip)"
  python3 - "$dir" "$zip" <<'PY'
import sys, os, zipfile
src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(src):
        for f in files:
            p = os.path.join(root, f)
            z.write(p, os.path.relpath(p, src))
PY
  local id
  id=$(curl -sf -X POST "$API/packages" -F "file=@$zip" | python3 -c "import json,sys; print(json.load(sys.stdin)['data']['id'])")
  rm -f "$zip"
  echo "$id"
}
PKG_LEAD=$(upload "$HERE/packages/fulfillment-lead")
PKG_ORDER=$(upload "$HERE/packages/order-agent")
PKG_INV=$(upload "$HERE/packages/inventory-agent")
PKG_LOG=$(upload "$HERE/packages/logistics-agent")
PKG_AS=$(upload "$HERE/packages/after-sales-agent")
echo "packageId: lead=$PKG_LEAD order=$PKG_ORDER inventory=$PKG_INV logistics=$PKG_LOG after-sales=$PKG_AS"

echo "== 3. 组装 env（沿用现网 release-agent 的平台基础设施值）=="
export NS TOKEN
envjson() { # $1=role(lead|member) → 输出 {env:..., secretKeys:[...]} JSON
  python3 - "$1" <<'PY'
import json, os, subprocess, base64, sys
role, ns, token = sys.argv[1], os.environ["NS"], os.environ["TOKEN"]
def k8s(cmd): return subprocess.check_output(cmd, shell=True, text=True)
cm = json.loads(k8s(f"kubectl -n {ns} get cm oaf-release-agent-env -o json"))
sec = json.loads(k8s(f"kubectl -n {ns} get secret oaf-release-agent-env-secret -o json"))
env = {k: v for k, v in cm["data"].items()}
env.update({k: base64.b64decode(v).decode() for k, v in sec["data"].items()})
env["SANDBOX_ENABLED"] = "false"  # demo 成员/主管不需要沙箱 shell
if role == "member":
    env["AGENT_PROTOCOL_ENABLED"] = "true"
    env["AGENT_PROTOCOL_AUTH_TOKEN"] = token
    secret_keys = ["AGENT_PROTOCOL_AUTH_TOKEN", "AGENT_REDIS_URL", "CHECKPOINT_PASSWORD", "LLM_API_KEY", "OPENSANDBOX_API_KEY"]
else:  # lead
    env["AGENT_REMOTE_HEADERS_JSON"] = json.dumps({"X-Agent-Protocol-Token": token})
    env["AGENT_PROTOCOL_ENABLED"] = "false"
    secret_keys = ["AGENT_REMOTE_HEADERS_JSON", "AGENT_REDIS_URL", "CHECKPOINT_PASSWORD", "LLM_API_KEY", "OPENSANDBOX_API_KEY"]
print(json.dumps({"env": env, "secretKeys": secret_keys}))
PY
}

ORDER_ENV=$(envjson member)
INV_ENV=$(envjson member)
LOG_ENV=$(envjson member)
AS_ENV=$(envjson member)
LEAD_ENV=$(envjson lead)

echo "== 4. 发布三个服务 =="
publish() { # $1=name $2=packageId $3=envJSON
  curl -sf -X POST "$API/services" -H 'Content-Type: application/json' \
    -d "$(python3 -c "import json,sys; print(json.dumps({'packageId': int(sys.argv[1]), 'name': sys.argv[2], 'image': sys.argv[3], 'replicas': 1, **json.loads(sys.argv[4])}))" "$2" "$1" "$IMAGE" "$3")"
}
publish fulfillment-lead "$PKG_LEAD" "$LEAD_ENV" | python3 -c "import json,sys; d=json.load(sys.stdin)['data']; print('fulfillment-lead:', d['id'], d['status'])"
publish order-agent "$PKG_ORDER" "$ORDER_ENV" | python3 -c "import json,sys; d=json.load(sys.stdin)['data']; print('order-agent:', d['id'], d['status'])"
publish inventory-agent "$PKG_INV" "$INV_ENV" | python3 -c "import json,sys; d=json.load(sys.stdin)['data']; print('inventory-agent:', d['id'], d['status'])"
publish logistics-agent "$PKG_LOG" "$LOG_ENV" | python3 -c "import json,sys; d=json.load(sys.stdin)['data']; print('logistics-agent:', d['id'], d['status'])"
publish after-sales-agent "$PKG_AS" "$AS_ENV" | python3 -c "import json,sys; d=json.load(sys.stdin)['data']; print('after-sales-agent:', d['id'], d['status'])"

echo "== 5. 等待就绪与注册 =="
for name in fulfillment-lead order-agent inventory-agent logistics-agent after-sales-agent; do
  for i in $(seq 1 60); do
    st=$(curl -sf "$API/services?keyword=$name" | python3 -c "import json,sys; d=json.load(sys.stdin)['data']; print(d[0]['status'] if d else 'none')" 2>/dev/null || echo none)
    [ "$st" = "running" ] || [ "$st" = "registered" ] && { echo "$name: $st"; break; }
    [ $i -eq 60 ] && { echo "$name: 超时（status=$st）"; exit 1; }
    sleep 5
  done
done
echo "== 完成：demo token=$TOKEN =="
echo "入口：http://127.0.0.1:8911/agent/{fulfillment-lead,order-agent,inventory-agent,logistics-agent,after-sales-agent}/"

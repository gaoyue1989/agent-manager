#!/usr/bin/env bash
# browser-takeover demo 一键部署（经平台 API 真实发布）
# 用法：
#   ./deploy.sh                  # 沙箱镜像已存在则复用；发布/更新服务
#   IMAGE_FORCE=1 ./deploy.sh    # 强制重建沙箱镜像
#   WITH_AGENT_FRAMEWORK=1 ./deploy.sh   # 顺带构建并导入 agent-framework 镜像
#   RECREATE=1 ./deploy.sh       # 已存在同名服务时先删除再发布
#   ./deploy.sh down             # 下线并删除演示服务
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
API="${API:-http://127.0.0.1:8911/api/v1}"
NS="${NS:-agent-platform}"
SERVICE="${SERVICE:-browser-takeover}"
IMAGE="${IMAGE:-172.20.0.1:5001/agent-framework:latest}"
SANDBOX_IMAGE="${SANDBOX_IMAGE:-172.20.0.1:5001/opensandbox-chrome-novnc:demo}"
KIND_NODE="${KIND_NODE:-agent-manager-control-plane}"
SECRETS_FILE="${SECRETS_FILE:-$REPO/.env.secrets}"
OSB_SERVER_URL="${OSB_SERVER_URL:-http://opensandbox-server.opensandbox-system.svc:80}"

log() { echo "[$(date '+%H:%M:%S')] $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

require() {
  for bin in "$@"; do
    command -v "$bin" >/dev/null 2>&1 || die "缺少 $bin"
  done
}

api_key() {
  grep -E '^OPENSANDBOX_API_KEY=' "$SECRETS_FILE" | head -1 | cut -d= -f2- | tr -d '"'
}

service_id_by_name() {
  curl -sf "$API/services?keyword=$SERVICE" | python3 -c "
import json, sys
name = sys.argv[1]
for item in (json.load(sys.stdin).get('data') or []):
    if item.get('name') == name:
        print(item['id'])
        break
" "$SERVICE"
}

import_kind() {
  local img="$1" force="${2:-0}"
  if [ "$force" != "1" ] && docker exec "$KIND_NODE" ctr --namespace=k8s.io images ls -q 2>/dev/null | grep -qxF "$img"; then
    log "kind 已有镜像: $img"
    return 0
  fi
  docker save "$img" | docker exec -i "$KIND_NODE" ctr --namespace=k8s.io images import --digests - >/dev/null
  log "已导入 kind: $img"
}

push_registry() {
  local img="$1"
  if docker push "$img" >/dev/null 2>&1; then
    log "已推送 registry: $img"
  else
    log "WARN registry 推送失败（kind 已导入，本集群运行不受影响）: $img"
  fi
}

build_sandbox_image() {
  if docker image inspect "$SANDBOX_IMAGE" >/dev/null 2>&1 && [ "${IMAGE_FORCE:-0}" != "1" ]; then
    log "沙箱镜像已存在: $SANDBOX_IMAGE（IMAGE_FORCE=1 可强制重建）"
  else
    log "构建沙箱镜像: $SANDBOX_IMAGE"
    docker build -t "$SANDBOX_IMAGE" "$HERE/sandbox-image"
  fi
  push_registry "$SANDBOX_IMAGE"
  import_kind "$SANDBOX_IMAGE"
}

build_agent_framework_image() {
  local module="$REPO/agent-framework"
  [ -f "$module/docker/otel/opentelemetry-javaagent.jar" ] \
    || die "缺少 $module/docker/otel/opentelemetry-javaagent.jar（先在 agent-framework 执行 make otel-agent）"
  log "打包 agent-framework fat jar"
  ( cd "$module" && mvn -q -o clean package -DskipTests )
  local dockerfile="$module/Dockerfile"
  [ -f "$module/Dockerfile.localjar" ] && dockerfile="$module/Dockerfile.localjar"
  log "构建 agent-framework 镜像（Dockerfile: $(basename "$dockerfile")）"
  docker build -q -f "$dockerfile" -t "$IMAGE" "$module"
  push_registry "$IMAGE"
  import_kind "$IMAGE" 1
}

upload_package() {
  local zip id
  zip="$(mktemp --suffix=.zip)"
  python3 - "$HERE/packages/browser-agent" "$zip" <<'PY'
import os, sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(src):
        for f in sorted(files):
            p = os.path.join(root, f)
            z.write(p, os.path.relpath(p, src))
PY
  id=$(curl -sf -X POST "$API/packages" -F "file=@$zip" | python3 -c "import json,sys; print(json.load(sys.stdin)['data']['id'])")
  rm -f "$zip"
  echo "$id"
}

derive_env() {
  local key="$1"
  NS="$NS" python3 - "$SANDBOX_IMAGE" "$OSB_SERVER_URL" "$key" <<'PY'
import base64, json, os, subprocess, sys
ns = os.environ["NS"]

def get(kind, name):
    return json.loads(subprocess.check_output(["kubectl", "-n", ns, "get", kind, name, "-o", "json"], text=True))

cm = get("cm", "oaf-release-agent-env")["data"]
sec = {k: base64.b64decode(v).decode() for k, v in get("secret", "oaf-release-agent-env-secret")["data"].items()}
merged = dict(cm)
merged.update(sec)

keep = ["LLM_API_KEY", "LLM_BASE_URL", "LLM_MODEL", "LLM_MODEL_ID",
        "CHECKPOINT_USERNAME", "CHECKPOINT_PASSWORD", "AGENT_REDIS_URL"]
env = {k: merged[k] for k in keep if merged.get(k)}
env.update({
    "AGENT_BROWSER_ENABLED": "true",
    "AGENT_BROWSER_TAKEOVER_TTL_MINUTES": "60",
    "SANDBOX_ENABLED": "true",
    "SANDBOX_IMAGE": sys.argv[1],
    "SANDBOX_ENTRYPOINT": "/browser-entrypoint.sh",
    "SANDBOX_MEMORY_MB": "2048",
    "SANDBOX_CPU_COUNT": "2",
    "SANDBOX_TIMEOUT_MINUTES": "60",
    "SANDBOX_USER_QUOTA": "2",
    "OPENSANDBOX_SERVER_URL": sys.argv[2],
    "OPENSANDBOX_API_KEY": sys.argv[3],
})
print(json.dumps({"env": env, "secretKeys": ["LLM_API_KEY", "CHECKPOINT_PASSWORD",
                                             "AGENT_REDIS_URL", "OPENSANDBOX_API_KEY"]}))
PY
}

wait_running() {
  local id="$1"
  for _ in $(seq 1 90); do
    local status
    status=$(curl -sf "$API/services/$id" | python3 -c "import json,sys; print(json.load(sys.stdin)['data']['status'])" 2>/dev/null || echo unknown)
    case "$status" in
      running|registered) log "服务状态: $status"; return 0 ;;
      failed) die "服务发布失败（status=failed），查看 kubectl -n $NS logs deploy/oaf-$SERVICE" ;;
    esac
    sleep 5
  done
  die "等待服务 running 超时"
}

down() {
  require kubectl curl python3
  local id
  id="$(service_id_by_name || true)"
  [ -n "${id:-}" ] || { log "服务 $SERVICE 不存在"; return 0; }
  curl -sf -X POST "$API/services/$id/unpublish" >/dev/null 2>&1 || true
  curl -sf -X DELETE "$API/services/$id" >/dev/null
  log "已删除服务 $SERVICE (id=$id)"
}

up() {
  require kubectl docker curl python3
  [ -f "$SECRETS_FILE" ] || die "未找到 $SECRETS_FILE"
  free -m | sed -n '1,2p'

  build_sandbox_image
  if [ "${WITH_AGENT_FRAMEWORK:-0}" = "1" ]; then
    build_agent_framework_image
  else
    log "复用 agent-framework 镜像: $IMAGE（WITH_AGENT_FRAMEWORK=1 可重建）"
    docker image inspect "$IMAGE" >/dev/null 2>&1 || die "本地无 $IMAGE，先 WITH_AGENT_FRAMEWORK=1 构建"
    import_kind "$IMAGE" 1
  fi

  log "应用 platform-router 清单（WebSocket 升级透传，幂等）"
  kubectl apply -f "$REPO/manifests/platform-router.yaml" >/dev/null

  local key id existing env_json
  key="$(api_key)"
  [ -n "$key" ] || die ".env.secrets 缺 OPENSANDBOX_API_KEY"

  log "上传 OAF 包"
  id="$(upload_package)"
  log "packageId=$id"

  env_json="$(derive_env "$key")"
  existing="$(service_id_by_name || true)"

  if [ -n "$existing" ]; then
    if [ "${RECREATE:-0}" = "1" ]; then
      log "RECREATE=1：删除旧服务 id=$existing"
      curl -sf -X POST "$API/services/$existing/unpublish" >/dev/null 2>&1 || true
      curl -sf -X DELETE "$API/services/$existing" >/dev/null
      existing=""
    else
      log "更新既有服务 id=$existing 的环境变量并重新发布"
      curl -sf -X PATCH "$API/services/$existing/env" -H 'Content-Type: application/json' \
        -d "$(python3 -c "import json,sys; e=json.loads(sys.argv[1]); print(json.dumps({'env': e['env'], 'secretKeys': e['secretKeys']}))" "$env_json")" >/dev/null
      curl -sf -X POST "$API/services/$existing/republish" -H 'Content-Type: application/json' -d '{}' >/dev/null
    fi
  fi

  if [ -z "$existing" ]; then
    log "发布服务 $SERVICE"
    existing=$(curl -sf -X POST "$API/services" -H 'Content-Type: application/json' \
      -d "$(python3 -c "import json,sys; e=json.loads(sys.argv[1]); print(json.dumps({'packageId': int(sys.argv[2]), 'name': sys.argv[3], 'image': sys.argv[4], 'replicas': 1, 'env': e['env'], 'secretKeys': e['secretKeys']}))" "$env_json" "$id" "$SERVICE" "$IMAGE")" \
      | python3 -c "import json,sys; print(json.load(sys.stdin)['data']['id'])")
  fi

  log "等待服务就绪（首次会拉起沙箱镜像，约 1-2 分钟）"
  wait_running "$existing"

  cat <<EOF

==================== 部署完成 ====================
服务：       $SERVICE (id=$existing)
Agent API：  http://127.0.0.1:30080/agent/$SERVICE/
服务入口：   http://127.0.0.1:8911/agent/$SERVICE/（宿主机 nginx，WebSocket 需先给 /agent/ 补升级头）
前端：
  cd $HERE/frontend
  npm install
  npm run dev          # http://<主机IP>:5188（监听 0.0.0.0）
e2e：
  node $HERE/e2e/browser-takeover-e2e.mjs
演示站点（沙箱内）：http://127.0.0.1:8088/  账号 demo / 密码见 DEMO_GUIDE.md
下线：       $HERE/deploy.sh down
=================================================
EOF
}

case "${1:-up}" in
  up) up ;;
  down) down ;;
  *) die "用法: $0 [up|down]" ;;
esac

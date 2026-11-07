#!/usr/bin/env bash
# deploy-opensandbox.sh — 在 kind 集群上幂等部署 OpenSandbox release-1.1.0
# 设计文档: docs/design/sandbox-pool-opensandbox-design.md §8（验收记录 §11，完整重跑 exit=0）
# 用法: ./scripts/deploy-opensandbox.sh   （端口冲突换 PF_PORT=xxxx 重跑；详见 docs/deployment.md
#        「OpenSandbox 部署与池初始化」章节——首个池的创建步骤与业务服务 env 示例也在那里）
#
# 流程: helm 检查 → 上游源码 → 镜像补拉(kind load) → ns/API key secret
#       → helm install base → controller → ingress-gateway → server → 自检(/health)
#
# 幂等性（每步机制，可任意重跑）:
#   0-1. 前置检查直通；helm 未装则经代理安装一次（装后幂等直通）
#   2.   上游源码目录已存在则校验 BOM 版本后复用，不重复 clone
#   3.   镜像: docker 本地已有 → 跳过拉取；kind 节点 ctr 已有 → 跳过载入
#   4.   ns 已存在跳过创建；secret 存在则 patch（值同源 .env.secrets，幂等）
#   5.   四个 release 均 helm upgrade --install（R+N 不触发滚动，§11 实测 pods age 不变、
#        池 warm Pod 不受扰动）；controller 前置的孤儿 RBAC 清理以「无 helm 所有权注解」
#        为判据，首次清理后幂等直通
#   6.   rollout status 幂等直通；port-forward 先 pkill 旧进程再起（PF_PORT 可换）
#
# 约束:
#   - 只创建 opensandbox-system / opensandbox 两个命名空间的资源，不触碰 agent-platform
#   - API key 从主工作区 .env.secrets 的 OPENSANDBOX_API_KEY 读入 shell 变量，不回显
#   - 网络: git/curl/docker 走 http://127.0.0.1:7890，慢则切备用 http://192.168.31.75:7890；
#     阿里云官方 mirror（chart 默认 registry，本机 No-Proxy 直连）作为镜像补拉的兜底
set -euo pipefail

PROXY="http://127.0.0.1:7890"
PROXY_BACKUP="http://192.168.31.75:7890"
KIND_CLUSTER="agent-manager"
SRC_DIR="/tmp/opensandbox-src"
CHARTS_DIR="$SRC_DIR/manifests/charts"
NS_SYS="opensandbox-system"
NS_WORK="opensandbox"
TAG="release-1.1.0"
CODE_INTERPRETER_TAG="v1.1.0"
MIRROR="sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox"
PF_LOG="/tmp/opensandbox-portforward.log"
# 注意: 本机 0.0.0.0:18080 被 docker 容器 agentscope-service-gateway-1 占用（勿动），
# 这里选干净端口并显式绑定 127.0.0.1；自检断言 /health 响应体 {"status":"healthy"}（防撞端口假阳性）
PF_PORT="${PF_PORT:-18600}"
SECRETS_FILE="/root/agent-manager/.env.secrets"
PULL_TIMEOUT=600

log() { echo "[$(date '+%F %T')] $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 0. 前置检查
# ---------------------------------------------------------------------------
for bin in kubectl docker kind git curl; do
  command -v "$bin" >/dev/null || die "缺少 $bin"
done
kubectl config current-context | grep -q "kind-${KIND_CLUSTER}" \
  || kubectl config use-context "kind-${KIND_CLUSTER}" >/dev/null
log "前置 OK: kubectl context=$(kubectl config current-context), helm=$(helm version --short 2>/dev/null || echo missing)"

# ---------------------------------------------------------------------------
# 1. helm（未安装则经代理安装）
# ---------------------------------------------------------------------------
if ! command -v helm >/dev/null 2>&1; then
  HELM_VER="v3.16.4"
  log "helm 未安装，经代理下载 ${HELM_VER}"
  curl -fsSL -x "$PROXY" -o /tmp/helm.tgz \
    "https://get.helm.sh/helm-${HELM_VER}-linux-amd64.tar.gz" \
    || curl -fsSL -x "$PROXY_BACKUP" -o /tmp/helm.tgz \
       "https://get.helm.sh/helm-${HELM_VER}-linux-amd64.tar.gz"
  tar -xzf /tmp/helm.tgz -C /tmp linux-amd64/helm
  install -m 0755 /tmp/linux-amd64/helm /usr/local/bin/helm
  rm -rf /tmp/helm.tgz /tmp/linux-amd64
fi
command -v helm >/dev/null 2>&1 || die "helm 安装失败"
log "helm: $(helm version --short)"

# ---------------------------------------------------------------------------
# 2. 上游源码（chart 来源；已存在则校验 release 版本后复用）
# ---------------------------------------------------------------------------
if [ ! -d "$SRC_DIR/.git" ]; then
  log "clone 上游仓库 release-1.1.0（走代理）"
  git -c http.proxy="$PROXY" -c https.proxy="$PROXY" \
    clone --depth 1 --branch release-1.1.0 \
    https://github.com/opensandbox-group/OpenSandbox "$SRC_DIR" \
    || git -c http.proxy="$PROXY_BACKUP" -c https.proxy="$PROXY_BACKUP" \
       clone --depth 1 --branch release-1.1.0 \
       https://github.com/opensandbox-group/OpenSandbox "$SRC_DIR"
fi
BOM_VER=$(grep -A2 '^metadata:' "$SRC_DIR/docs/releases/1.1.0.yaml" | grep version | awk '{print $2}')
[ "$BOM_VER" = "1.1.0" ] || die "上游源码不是 release-1.1.0（BOM version=$BOM_VER）"
for c in base controller ingress-gateway server; do
  [ -d "$CHARTS_DIR/$c" ] || die "缺 chart: $CHARTS_DIR/$c"
done
log "上游源码 OK: $SRC_DIR（BOM 1.1.0，chart 目录 manifests/charts/{base,controller,ingress-gateway,server}）"

# ---------------------------------------------------------------------------
# 3. 镜像补拉 → kind load（最小集：server/controller/ingress + code-interpreter/execd/task-executor，
#    tag 以 BOM docs/releases/1.1.0.yaml 为准，全部 release-1.1.0；code-interpreter 上游示例用 v1.1.0）
# ---------------------------------------------------------------------------
IMAGES=(
  "opensandbox/server:${TAG}"
  "opensandbox/controller:${TAG}"
  "opensandbox/ingress:${TAG}"
  "opensandbox/execd:${TAG}"
  "opensandbox/code-interpreter:${CODE_INTERPRETER_TAG}"
  "opensandbox/task-executor:${TAG}"
)

pull_one() {
  local img="$1"
  local name="${img%%:*}"
  if docker image inspect "$img" >/dev/null 2>&1; then
    log "镜像已存在: $img"
    return 0
  fi
  # 优先: 阿里云官方 mirror（chart 默认 registry、本机 No-Proxy 直连秒级完成，
  # digest 已与 BOM docs/releases/1.1.0.yaml 核验一致，如 task-executor
  # sha256:89e7af6d8ecdb58f...），拉后重打 docker.io tag
  local mirror_img="$MIRROR/${name#opensandbox/}:${img#*:}"
  log "mirror 拉取: $mirror_img"
  if timeout "$PULL_TIMEOUT" docker pull "$mirror_img" >/dev/null 2>&1; then
    docker tag "$mirror_img" "$img"
    log "拉取成功(mirror)并重打 tag: $img"
    return 0
  fi
  # 兜底: dockerd 已配代理（HTTP(S)_PROXY=127.0.0.1:7890）直拉 docker.io，限时防死磕
  log "回退 docker.io 代理拉取（限时 ${PULL_TIMEOUT}s）: $img"
  timeout "$PULL_TIMEOUT" docker pull "$img" >/dev/null 2>&1 || return 1
  log "拉取成功(docker.io/代理): $img"
  return 0
}

KIND_NODE="$KIND_CLUSTER-control-plane"

# 载入节点：docker(29.x, containerd image store) save 的 OCI index 缺其他平台 blob，
# kind load(ctr import --all-platforms) 会报 content digest not found —— 改手工 ctr import
# （仅导入本地平台），并以节点侧 ctr images 做幂等检查。
node_has_img() {
  docker exec "$KIND_NODE" ctr --namespace=k8s.io images ls -q 2>/dev/null \
    | grep -qx "docker.io/${1#docker.io/}"
}
load_one() {
  local img="$1"
  if node_has_img "$img"; then
    log "节点已有镜像: $img"
    return 0
  fi
  docker save "$img" | docker exec -i "$KIND_NODE" ctr --namespace=k8s.io images import --digests - >/dev/null 2>&1 \
    || die "ctr import 失败: $img"
  node_has_img "$img" || die "载入后节点仍不可见: $img"
  log "已载入节点: $img"
}

for img in "${IMAGES[@]}"; do
  pull_one "$img" || die "镜像拉取失败: $img（代理与 mirror 均不可用）"
  load_one "$img"
done
log "镜像最小集已全部载入 kind"

# ---------------------------------------------------------------------------
# 4. 命名空间 + API key secret（值只进 shell 变量，不回显不落盘）
# ---------------------------------------------------------------------------
kubectl get ns "$NS_SYS" >/dev/null 2>&1 || kubectl create ns "$NS_SYS" >/dev/null
kubectl get ns "$NS_WORK" >/dev/null 2>&1 || kubectl create ns "$NS_WORK" >/dev/null
[ -f "$SECRETS_FILE" ] || die "未找到 $SECRETS_FILE"
OPENSANDBOX_API_KEY="$(grep -E '^OPENSANDBOX_API_KEY=' "$SECRETS_FILE" | head -1 | cut -d= -f2- | tr -d '\"')"
[ -n "$OPENSANDBOX_API_KEY" ] || die ".env.secrets 缺 OPENSANDBOX_API_KEY"
if kubectl -n "$NS_SYS" get secret opensandbox-api-key >/dev/null 2>&1; then
  kubectl -n "$NS_SYS" patch secret opensandbox-api-key \
    -p "{\"stringData\":{\"api-key\":\"$OPENSANDBOX_API_KEY\"}}" >/dev/null
else
  kubectl -n "$NS_SYS" create secret generic opensandbox-api-key \
    --from-literal=api-key="$OPENSANDBOX_API_KEY" >/dev/null
fi
unset OPENSANDBOX_API_KEY
log "ns/$NS_WORK 就绪; secret opensandbox-api-key 已注入（值不回显）"

# ---------------------------------------------------------------------------
# 5. helm install（幂等 upgrade --install）: base → controller → ingress-gateway → server
#    ns=opensandbox-system（workload ns=opensandbox）; 镜像统一改用 docker.io/opensandbox（已 kind load）
# ---------------------------------------------------------------------------
helm_flags=(--namespace "$NS_SYS" --create-namespace)

# 5.1 base: CRD(batchsandboxes/pools/sandboxsnapshots) + RBAC；fast-sandbox 本环境不装
helm upgrade --install opensandbox-base "$CHARTS_DIR/base" "${helm_flags[@]}" \
  --set fastSandbox.crds.install=false \
  --set fastSandbox.rbac.create=false \
  --set fastSandbox.namespaces.create=false \
  >/dev/null
log "base 安装完成（CRD + RBAC）"

# 5.2 controller: BatchSandbox/Pool 调谐器（池预热/认领/recycle）
# 幂等前置：清理 2026-11-05 部署尝试遗留的孤儿 RBAC（无 helm 所有权注解、无使用方，
# 属 opensandbox 部署自身遗留物，helm 重建同名资源；不触碰其他组件任何资源）
for cr in opensandbox-manager-role; do
  if kubectl get clusterrole "$cr" >/dev/null 2>&1 \
    && ! kubectl get clusterrole "$cr" -o jsonpath='{.metadata.annotations}' \
      | grep -q "meta.helm.sh/release-name"; then
    kubectl delete clusterrole "$cr" --ignore-not-found >/dev/null
    kubectl delete clusterrolebinding "$cr" --ignore-not-found >/dev/null
    log "已清理孤儿 RBAC: $cr（无 helm 所有权，重建于本次安装）"
  fi
done
helm upgrade --install opensandbox-controller "$CHARTS_DIR/controller" "${helm_flags[@]}" \
  --set controller.image.repository=docker.io/opensandbox/controller \
  --set controller.image.tag="$TAG" \
  --set controller.snapshot.imageCommitterImage=docker.io/opensandbox/image-committer:${TAG} \
  >/dev/null
log "controller 安装完成"

# 5.3 ingress-gateway: 沙箱数据面网关（K8s 上沙箱 Pod ClusterIP-only，流量走网关）
helm upgrade --install opensandbox-ingress-gateway "$CHARTS_DIR/ingress-gateway" "${helm_flags[@]}" \
  --set gateway.image.repository=docker.io/opensandbox/ingress \
  --set gateway.image.tag="$TAG" \
  --set gateway.resources.requests.cpu=100m \
  --set gateway.resources.requests.memory=128Mi \
  --set gateway.resources.limits.cpu=500m \
  --set gateway.resources.limits.memory=512Mi \
  >/dev/null
log "ingress-gateway 安装完成"

# 5.4 server: REST API 面（/v1/sandboxes /v1/pools）
#   configToml 按 §2.5: runtime.type=kubernetes / workload_provider=batchsandbox /
#   workload ns=opensandbox / execd_image=BOM tag；SQLite 默认持久化（POC 规模）
#   api_key 经 env OPENSANDBOX_SERVER_API_KEY ← secret（config.py API_KEY_ENV_VAR 覆盖，值不落 ConfigMap）
SERVER_TOML=$(cat <<'TOML'
[server]
host = "0.0.0.0"
port = 80
api_key = ""

[log]
level = "INFO"

[runtime]
type = "kubernetes"
execd_image = "opensandbox/execd:release-1.1.0"

[kubernetes]
kubeconfig_path = ""
namespace = "opensandbox"
informer_resync_seconds = 300
informer_watch_timeout_seconds = 60
workload_provider = "batchsandbox"
batchsandbox_template_file = "/etc/opensandbox/example.batchsandbox-template.yaml"
sandbox_create_timeout_seconds = 60

[egress]
image = "opensandbox/egress:v1.1.7"
mode = "dns+nft"
TOML
)
helm upgrade --install opensandbox-server "$CHARTS_DIR/server" "${helm_flags[@]}" \
  --set server.image.repository=docker.io/opensandbox/server \
  --set server.image.tag="$TAG" \
  --set server.replicaCount=1 \
  --set server.resources.requests.cpu=250m \
  --set server.resources.requests.memory=512Mi \
  --set server.resources.limits.cpu=1 \
  --set server.resources.limits.memory=2Gi \
  --set-string server.env[0].name=OPENSANDBOX_SERVER_API_KEY \
  --set server.env[0].valueFrom.secretKeyRef.name=opensandbox-api-key \
  --set server.env[0].valueFrom.secretKeyRef.key=api-key \
  --set-file configToml=<(printf '%s\n' "$SERVER_TOML") \
  >/dev/null
log "server 安装完成（config: runtime=kubernetes, provider=batchsandbox, ns=opensandbox, SQLite）"

# ---------------------------------------------------------------------------
# 6. 自检: 三个 Deployment Available + /health 通（port-forward 后台保持）
# ---------------------------------------------------------------------------
for d in opensandbox-controller-manager opensandbox-ingress-gateway opensandbox-server; do
  kubectl -n "$NS_SYS" rollout status "deployment/$d" --timeout=300s >/dev/null \
    || die "deployment 未就绪: $d"
  log "deployment Available: $d"
done

# 端口转发幂等: 清掉旧的 opensandbox-server port-forward 再起（显式绑 127.0.0.1）
pkill -f "port-forward.*opensandbox-server" 2>/dev/null || true
sleep 1
kubectl -n "$NS_SYS" port-forward --address 127.0.0.1 \
  svc/opensandbox-server "${PF_PORT}:80" >"$PF_LOG" 2>&1 &
PF_PID=$!
HEALTH_OK=""
for i in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:${PF_PORT}/health" 2>/dev/null | grep -q '"status":"healthy"'; then
    HEALTH_OK=1; break
  fi
  sleep 1
done
[ -n "$HEALTH_OK" ] || die "/health 30s 内未返回 {\"status\":\"healthy\"}（见 $PF_LOG；若端口被占请换 PF_PORT）"
log "自检通过: /health {\"status\":\"healthy\"} @ http://127.0.0.1:${PF_PORT}（port-forward pid=$PF_PID 保持后台）"

# ---------------------------------------------------------------------------
# 输出业务服务接入指引
# ---------------------------------------------------------------------------
cat <<EOF

==================== 部署完成 ====================
集群内地址:  http://opensandbox-server.${NS_SYS}.svc:80
本机地址:    http://127.0.0.1:${PF_PORT}  (kubectl port-forward 保持中, 日志 ${PF_LOG})
API key:     已注入 secret ${NS_SYS}/opensandbox-api-key（.env.secrets OPENSANDBOX_API_KEY 同源）
             业务请求带 header: OPEN-SANDBOX-API-KEY
接入 env:    OPENSANDBOX_SERVER_URL=http://opensandbox-server.${NS_SYS}.svc:80
             OPENSANDBOX_API_KEY=<同 .env.secrets>
workload ns: ${NS_WORK}   system ns: ${NS_SYS}
下一步:      运维侧 /settings 填入上面两个值 → /admin/sandboxes 创建首个池
             （或 docs/deployment.md「OpenSandbox 部署与池初始化」的 curl 步骤）
=================================================
EOF

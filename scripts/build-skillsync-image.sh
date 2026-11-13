#!/usr/bin/env bash
# build-skillsync-image.sh — 构建 code-interpreter-oa 技能同步 overlay 镜像并导入 kind
# （skills-syncd 设计 §5.1 镜像集成；docs/deployment.md「五之二 → 4. 技能同步」）
# 用法: ./scripts/build-skillsync-image.sh   （幂等可重跑：节点已有该镜像即跳过导入）
#
# 流程: 宿主静态编译 skills-syncd（产物 skills-syncd/dist/skills-syncd，gitignore 内）
#       → 组装最小 build context → docker build -f skills-syncd/docker/Dockerfile.oa-overlay
#       → 按 scripts/deploy-opensandbox.sh 的 load_one 模式导入 kind 节点
#         （docker 29.x containerd image store 的 save 产物 OCI index 缺其他平台 blob，
#          ctr import --all-platforms 会报 content digest not found —— 手工 ctr import
#          仅导入本地平台，节点侧 ctr images ls 幂等校验）
#
# 前置: go + docker；kind 集群 agent-manager（节点 agent-manager-control-plane）；
#       基础镜像 gaoyue1989/code-interpreter-oa:v20260914 已在本地（12.7GB，导入需数分钟）
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SSD_DIR="$REPO_ROOT/skills-syncd"
BASE_IMAGE="gaoyue1989/code-interpreter-oa:v20260914"
IMAGE="gaoyue1989/code-interpreter-oa:skillsync-331"
KIND_NODE="agent-manager-control-plane"

log() { echo "[$(date '+%F %T')] $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

for bin in go docker git; do
  command -v "$bin" >/dev/null || die "缺少 $bin"
done
docker image inspect "$BASE_IMAGE" >/dev/null 2>&1 || die "本地缺基础镜像: $BASE_IMAGE"
docker ps --format '{{.Names}}' | grep -qx "$KIND_NODE" || die "kind 节点容器不在运行: $KIND_NODE"

# 1. 宿主静态编译（与 skills-syncd/Dockerfile.build 同参：trimpath + strip + buildSHA 注入）
BUILD_SHA="$(git -C "$REPO_ROOT" rev-parse --short HEAD)"
log "go build（CGO_ENABLED=0 GOOS=linux GOARCH=amd64）buildSHA=$BUILD_SHA"
mkdir -p "$SSD_DIR/dist"
(cd "$SSD_DIR" && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
  go build -trimpath -ldflags="-s -w -X main.buildSHA=$BUILD_SHA" -o dist/skills-syncd .)
[ -x "$SSD_DIR/dist/skills-syncd" ] || die "编译产物缺失: $SSD_DIR/dist/skills-syncd"
log "编译产物: $(du -h "$SSD_DIR/dist/skills-syncd" | cut -f1) → $SSD_DIR/dist/skills-syncd"

# 2. 最小 build context（二进制 + 两脚本）→ docker build overlay
STAGE="$(mktemp -d /tmp/skillsync-image-context.XXXXXX)"
trap 'rm -rf "$STAGE"' EXIT
cp "$SSD_DIR/dist/skills-syncd" "$STAGE/skills-syncd"
cp "$SSD_DIR/docker/skillsync-launcher.sh" "$SSD_DIR/docker/entrypoint-wrap.sh" "$STAGE/"
log "docker build: $IMAGE"
docker build -q -f "$SSD_DIR/docker/Dockerfile.oa-overlay" -t "$IMAGE" "$STAGE" >/dev/null \
  || die "docker build 失败: $IMAGE"
log "镜像构建完成: $IMAGE"

# 3. 导入 kind 节点（load_one 模式）+ ctr images ls 幂等校验
node_has_img() {
  docker exec "$KIND_NODE" ctr --namespace=k8s.io images ls -q 2>/dev/null \
    | grep -qx "docker.io/${1#docker.io/}"
}
if node_has_img "$IMAGE"; then
  log "节点已有镜像（跳过导入）: $IMAGE"
else
  log "docker save | ctr import（基础镜像 12.7GB，需数分钟）"
  docker save "$IMAGE" | docker exec -i "$KIND_NODE" ctr --namespace=k8s.io images import --digests - >/dev/null 2>&1 \
    || die "ctr import 失败: $IMAGE"
fi
node_has_img "$IMAGE" || die "载入后节点仍不可见: $IMAGE"
log "已载入节点并经 ctr images ls 校验: $IMAGE"

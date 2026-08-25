# OAF 服务发布平台 — 部署指南（v2）

> 设计文档：[REDESIGN.md](../REDESIGN.md) ｜ 模块指引：各目录 AGENTS.md

## 前置条件

- Kind 单节点集群（`docs/kind-config.yaml`），ingress-nginx 已装（NodePort 30080）
- 宿主机已装 Docker、kubectl、Go 1.23+（`/usr/local/go1.23/bin/go`）、Maven+JDK21
- GOPROXY 走 `https://goproxy.cn,direct`

## 一、构建镜像

```bash
# 1) agent-framework（业务运行时，Java）
cd agent-framework && mvn -q clean package -DskipTests
docker build -t agent-framework:latest .

# 2) platform-backend（Go 管理后端 + MCP 同进程）
cd ../backend && docker build -t platform-backend:v1 .

# 3) platform-frontend（Next.js standalone）
cd ../frontend && docker build -t platform-frontend:v2 .
```

## 二、导入镜像到 Kind 节点

> 注意：必须用 `--platform linux/amd64` 导出（官方镜像含 arm64/attestation 条目会导致 ctr 导入失败）

```bash
for img in agent-framework:latest platform-backend:v1 platform-frontend:v2; do
  f=/tmp/opencode/$(echo $img | tr ':/' '__').tar
  docker save --platform linux/amd64 -o $f $img
  docker cp $f agent-manager-control-plane:/var/tmp/img.tar
  docker exec agent-manager-control-plane ctr -n k8s.io images import --all-platforms /var/tmp/img.tar
done
```

## 三、部署平台

```bash
cd manifests
kubectl apply -f platform.yaml          # ns/RBAC/PVC/MySQL8/platform-backend
kubectl apply -f platform-ingress.yaml # REST/MCP 入口
kubectl apply -f frontend.yaml         # 前端 NodePort 30881
kubectl -n agent-platform rollout status deployment --timeout=300s
```

## 四、自举发布助手

浏览器打开前端 → 上传 `release-agent/` 打包的 zip（或直接调 API）→ 发布：
镜像 `agent-framework:latest`，env 填 LLM_* 与 CHECKPOINT_JDBC_URL（见下）。

## 关键端点

| 入口 | 地址 |
|------|------|
| 统一入口（宿主机 nginx） | http://100.66.1.5:8911 |
| 前端直连 | http://172.20.0.3:30881 |
| REST API | http://localhost:30080/api/v1 |
| MCP | http://localhost:30080/mcp |
| 发布助手 Debug 页 | http://172.20.0.3:30080/agent/release-agent/debug |

## 凭据与敏感配置

| 项 | 值 |
|----|----|
| MySQL（oaf-mysql） | 用户 `oaf` / 密码 `OafPlatform2026`（root 同） |
| LLM | `.env.secrets`（mimo-v2.5） |

Checkpoint DSN 模板：
```
jdbc:mysql://oaf-mysql.agent-platform.svc.cluster.local:3306/oaf_checkpoint?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
```

## 运维备忘

1. **更新任一镜像后**：重新 save/import 后必须 `kubectl -n agent-platform rollout restart deployment/<name>`——同名 tag 不会自动触发滚动，且 Ingress 注解由 platform-backend 下发，改注解逻辑后必须重启它再 republish。
2. 业务 Pod 的 OAF 包挂载在 `/config`（只读）+ 工作区 `/workspace`（可写）。
3. MCP server 不可达默认不阻断启动（fail-soft）；必需依赖在包内写 `startup.required: true`。

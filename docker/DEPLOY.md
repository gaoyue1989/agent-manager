# Agent Manager — Docker Compose 部署指南

## 前置条件

| 依赖 | 版本要求 | 说明 |
|------|---------|------|
| Docker | >= 24.0 | 含 Docker Compose v2 |
| Kubernetes | Kind 本地集群 / 已有集群 | Agent Sandbox 部署目标 |
| kubectl | >= 1.28 | K8s 命令行工具 |
| Docker Registry | 本地运行 (端口 5001) | Agent 镜像仓库 |
| LLM API | — | 需提供 API Key / Endpoint |

**前置检查：**

```bash
docker info
kubectl cluster-info
docker ps | grep registry   # 确认本地 Registry 运行
df -h                        # 磁盘空间至少 10GB
```

---

## 快速开始

### 1. 进入 docker 目录

```bash
cd /path/to/agent-manager/docker
```

### 2. 创建并配置环境变量

```bash
cp .env.example .env

# 必须填写:
#   LLM_API_KEY, LLM_MODEL, LLM_ENDPOINT
#   BUILD_BASE_IMAGE=false  (除非 Docker Socket 可用且网络畅通)

# 端口冲突时修改:
#   MYSQL_HOST_PORT=3308     (宿主机 MySQL 占用 3307)
#   MINIO_API_HOST_PORT=9002 (其他 MinIO 占用 9000)
#   MINIO_CONSOLE_HOST_PORT=9003 (其他 MinIO 占用 9001)
```

### 3. 构建并启动

```bash
docker compose up -d --build
docker compose logs -f
```

### 4. 验证服务

```bash
docker compose ps

# 应看到 5 个服务全部 running:
# agent-manager-backend-1    Up
# agent-manager-frontend-1   Up
# agent-manager-mysql-1      Up (healthy)
# agent-manager-minio-1      Up
# agent-manager-nginx-1      Up
```

### 5. 验证 API

```bash
curl http://localhost:8911/api/v1/agents
# 返回 {"items":[],"total":0}

curl http://localhost:8911/
# 返回 200 (前端页面)
```

### 6. 访问服务

| 服务 | 地址 | 说明 |
|------|------|------|
| Web UI | `http://localhost:8911` | Agent Manager 前端界面 |
| API | `http://localhost:8911/api/v1` | 后端 REST API |
| MinIO Console | `http://localhost:MINIO_CONSOLE_HOST_PORT` | 对象存储管理面板 |
| MySQL | `localhost:MYSQL_HOST_PORT` | 数据库 |

> 端口取决于 `.env` 中的 `NGINX_PORT` / `MINIO_CONSOLE_HOST_PORT` / `MYSQL_HOST_PORT` 设置。

---

## 构建说明

### 默认构建 (Go 代理可达)

后端 Dockerfile 使用多阶段构建：golang 编译 → alpine 运行时。

```bash
docker compose up -d --build
```

### 离线构建 (Go 代理不可达)

当宿主机无法访问 `proxy.golang.org` 时，使用预编译二进制：

```bash
# 1. 在宿主机编译后端
cd backend && CGO_ENABLED=0 go build -o bin/server ./cmd/server

# 2. 修改 docker-compose.yml 后端构建配置:
#    build:
#      context: ..
#      dockerfile: backend/Dockerfile.prod

# 3. 构建
docker compose up -d --build
```

`Dockerfile.prod` 直接复制 `backend/bin/server` 二进制，跳过 Go 编译阶段。

> **注意**: `Dockerfile.prod` 的 `RUN apk add + pip install` 需要外网访问。若首次构建，需确保网络可达；若使用缓存层则无需。

---

## 环境变量详解

### 容器通用配置

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `RESTART_POLICY` | `unless-stopped` | 重启策略: `no` / `always` / `on-failure` / `unless-stopped` |

### Nginx

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `NGINX_IMAGE` | `nginx:alpine` | 镜像 |
| `NGINX_PORT` | `8911` | 对外端口 |

### MySQL (GreatSQL)

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MYSQL_IMAGE` | `greatsql/greatsql:8.0.32-25` | 镜像 |
| `MYSQL_ROOT_PASSWORD` | `root123` | root 密码 |
| `MYSQL_DATABASE` | `agent_manager` | 应用数据库名 |
| `MYSQL_USER` | `agent_manager` | 应用数据库用户 |
| `MYSQL_PASSWORD` | `Agent@Manager2026` | 应用数据库密码 |
| `MYSQL_HOST_PORT` | `3307` | 宿主机端口映射 |

### MinIO

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MINIO_IMAGE` | `minio/minio:latest` | 镜像 |
| `MINIO_ROOT_USER` | `minioadmin` | root 用户 |
| `MINIO_ROOT_PASSWORD` | `minioadmin` | root 密码 |
| `MINIO_API_HOST_PORT` | `9000` | API 宿主机端口 |
| `MINIO_CONSOLE_HOST_PORT` | `9001` | Console 宿主机端口 |

### 后端

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `SERVER_PORT` | `8080` | 后端监听端口 |
| `MYSQL_DSN` | (见 .env) | 数据库连接串 |
| `MINIO_ENDPOINT` | `minio:9000` | MinIO 地址 |
| `MINIO_ACCESS_KEY` | `minioadmin` | MinIO 访问密钥 |
| `MINIO_SECRET_KEY` | `minioadmin` | MinIO 密钥 |
| `MINIO_BUCKET` | `agent-manager` | Bucket 名 |

### Kubernetes

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `KUBE_CONFIG` | (空) | kubeconfig 路径 (空=`~/.kube/config`) |
| `KUBE_NAMESPACE` | `default` | K8s 命名空间 |
| `KUBE_INGRESS_CLASS` | `nginx` | Ingress Controller 类型 |
| `K8S_CLIENT_MODE` | `kubectl` | K8s 客户端模式 |

`K8S_CLIENT_MODE` 可选值:

| 值 | 实现 | 说明 |
|----|------|------|
| `kubectl` (默认) | KubectlClient | 基于 kubectl CLI，无需额外依赖 |
| `api` | K8sAPIClient | 基于 client-go SDK，需额外构建步骤 |

**启用 `api` 模式:**

```bash
# 下载 client-go 依赖 (需网络)
go get k8s.io/client-go@latest && go mod tidy

# 构建时加 -tags api
go build -tags api ./cmd/server

# 设置环境变量
export K8S_CLIENT_MODE=api
```

> 默认构建不含 client-go 依赖，`K8S_CLIENT_MODE=api` 会返回错误提示并回退。

### Docker Registry

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `LOCAL_REGISTRY` | `172.20.0.1:5001` | 本地 Registry 地址 |
| `DOCKER_USERNAME` | (空) | Registry 用户名 |
| `DOCKER_PASSWORD` | (空) | Registry 密码 |

### Agent 部署

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `INGRESS_HOST` | `localhost` | Ingress 对外地址 |
| `INGRESS_ENABLED` | `true` | 是否启用 Ingress |
| `DEPLOY_METHOD` | `sandbox` | 部署方式 |
| `DEPLOY_TEMPLATE_DIR` | (空) | 自定义模板目录 |

`DEPLOY_METHOD` 可选值:

| 值 | K8s 资源 | 说明 |
|----|---------|------|
| `sandbox` (默认) | `agents.x-k8s.io/v1alpha1/Sandbox` | Sandbox CRD 管理 Agent Pod |
| `deployment` | `apps/v1/Deployment` | 标准 Deployment，支持 ConfigMap/Secret 卷挂载，模板目录由 `DEPLOY_TEMPLATE_DIR` 指定 |

### 基础镜像

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `BASE_IMAGE_NAME` | `agent-base:latest` | 基础镜像名 |
| `BUILD_BASE_IMAGE` | `false` | 启动时自动构建基础镜像 |

`BUILD_BASE_IMAGE=true` 前提条件:

| 条件 | 说明 |
|------|------|
| Docker Socket 可用 | 需挂载 `volumes: ["/var/run/docker.sock:/var/run/docker.sock"]` |
| 非空凭证 | `DOCKER_USERNAME` / `DOCKER_PASSWORD` 非空 (本地 HTTP Registry 可留空跳过登录) |
| 网络畅通 | `Dockerfile.base` 中 `pip install` 需访问 PyPI |

不满足时设置 `BUILD_BASE_IMAGE=false`，使用已预构建的 `agent-base:latest` 缓存层。

### Agent 挂载模式

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `AVAILABLE_IMAGES` | `agent-framework:latest\|Agent Framework v0.5.5` | 可选镜像列表 (`名称\|描述`，`\|` 分隔) |
| `DEFAULT_IMAGE` | `agent-framework:latest` | 默认镜像 |
| `DEFAULT_CHECKPOINT_DSN` | (空) | Checkpoint 数据库 DSN |

> Checkpoint DSN 在 K8s Pod 内须用 `172.20.0.1` (Docker 网关)，不可用 `127.0.0.1`。

### LLM 配置 (必填)

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `LLM_API_KEY` | (空) | LLM API 密钥，注入 Pod 为 `LLM_API_KEY` (Secret) |
| `LLM_MODEL` | (空) | 模型名称，注入 Pod 为 `LLM_MODEL_ID` |
| `LLM_ENDPOINT` | (空) | API 端点，注入 Pod 为 `LLM_BASE_URL` |

> `LLM_PROVIDER` 在 sandbox 模板中硬编码为 `ctyun` (`backend/internal/k8s/sandbox.go`)，不可通过环境变量修改。

---

## Docker Compose 网络架构

```
浏览器 :8911
    │
    ▼
┌──────────┐
│  Nginx   │  nginx:alpine (:8911)
│  :8911   │
└──────────┘
  │          │            │
  │ /        │ /api/      │ /agent/
  ▼ :3000    ▼ :8080      ▼ host.docker.internal:30080
Frontend    Backend       K8s Ingress Controller
(Next.js)   (Go/Gin)      (宿主机 NodePort)
```

容器间通信通过网络 `agent-net` (bridge 模式):
- `frontend` ⇄ `backend` (通过服务名, 端口 8080)
- `backend` ⇄ `mysql` (通过服务名, 端口 3306)
- `backend` ⇄ `minio` (通过服务名, 端口 9000)
- `nginx` → `host.docker.internal` (访问宿主机 K8s Ingress)

> 注意: 容器内部通信用服务名 + 容器端口，宿主机端口映射仅用于外部访问。

---

## 常用运维命令

```bash
# 查看服务状态
docker compose ps

# 查看后端日志
docker compose logs -f backend

# 重启单个服务
docker compose restart backend

# 停止所有服务
docker compose down

# 停止并清理数据卷 (危险!)
docker compose down -v

# 重新构建并启动
docker compose up -d --build

# 资源使用
docker stats

# 项目名指定 (避免与其他 compose 冲突)
docker compose -p agent-manager ps
```

---

## 故障排查

### 后端 Restarting — `failed to build base image: login:`

```bash
# 原因: BUILD_BASE_IMAGE=true 但 Docker Socket 未挂载或凭证为空
# 修复: 设置 BUILD_BASE_IMAGE=false
# 或: docker-compose.yml 中挂载 Socket:
#   volumes:
#     - /var/run/docker.sock:/var/run/docker.sock
```

### MySQL 端口被占用

```bash
# 检查占用
ss -tlnp | grep :3307
# 修改 .env 中 MYSQL_HOST_PORT 为其他端口 (如 3308)
```

### MinIO 端口被占用

```bash
# 检查占用
ss -tlnp | grep :9000
# 修改 .env:
#   MINIO_API_HOST_PORT=9002
#   MINIO_CONSOLE_HOST_PORT=9003
```

### Go 代理不可达 (`go mod download` 超时)

```bash
# 症状: 后端构建 180s 后超时
# 原因: Docker 容器内 go mod download 无法访问 proxy.golang.org
# 方案: 使用 Dockerfile.prod (预编译二进制，见"构建说明 - 离线构建")
```

### 后端无法连接 K8s

```bash
# 确认 kubeconfig 存在
docker compose exec backend kubectl cluster-info

# 若 kubeconfig 路径不同，设置 KUBE_CONFIG
```

### MinIO Bucket 未自动创建

```bash
# 手动创建: 访问 MinIO Console → 创建 bucket: agent-manager
# 或设置 MINIO_BUCKET 为自定义名称
```

---

## 生产环境注意事项

1. **修改默认密码**: `.env` 中 `MYSQL_ROOT_PASSWORD`, `MYSQL_PASSWORD`, `MINIO_ROOT_PASSWORD`
2. **配置 LLM**: 必须填写 `LLM_API_KEY`, `LLM_MODEL`, `LLM_ENDPOINT`
3. **INGRESS_HOST**: 设为实际 nginx 入口地址 (如 `192.168.1.100:8911`)
4. **数据持久化**: `mysql_data` / `minio_data` 卷默认持久化，`docker compose down -v` 删除
5. **敏感变量**: LLM_API_KEY 等通过 `docker compose --env-file .env.secrets` 注入
6. **Restart 策略**: 默认 `unless-stopped`，容器异常退出自动重启

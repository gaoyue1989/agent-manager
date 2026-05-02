# Agent Manager

Agent 管理平台 — 通过页面/JSON/YAML 配置 Agent，自动生成 DeepAgents 代码，打包为 Docker 镜像，部署到 Kubernetes 的 agent-sandbox 隔离环境中运行，并管理 Agent 的发布/下线生命周期。

---

## 系统架构

```
┌──────────────────────────────────────────────────────────────┐
│              开发机 (Ubuntu 24.04 / 4C8G)                     │
│                                                               │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────┐ │
│  │  Dify    │  │ GreatSQL │  │  MinIO   │  │ K8s (Kind)   │ │
│  │ (Docker) │  │ (3307)   │  │ (9000)   │  │              │ │
│  │          │  │          │  │          │  │ agent-       │ │
│  │ nginx:80 │  │ agent_   │  │ agent-   │  │ sandbox      │ │
│  │          │  │ manager  │  │ manager  │  │ Controller   │ │
│  │          │  │  DB      │  │  Bucket  │  │ Sandbox CRD  │ │
│  └──────────┘  └──────────┘  └──────────┘  └──────────────┘ │
│                                                               │
│  ┌──────────────────────────────────────────────────────────┐│
│  │             Agent Manager 应用                             ││
│  │  ┌─────────────┐    ┌───────────────────────────────────┐ ││
│  │  │ Go 后端:8080│────│ Next.js 前端:3000                  │ ││
│  │  │ Gin + GORM  │    │ React 19 + Tailwind CSS           │ ││
│  │  └──────┬──────┘    └───────────────────────────────────┘ ││
│  │         │                                                  ││
│  │  ┌──────▼──────────────────────────────────────────────┐  ││
│  │  │ Python CodeGen → FastAPI + DeepAgents 微服务          │  ││
│  │  │ Docker Build  → K8s Sandbox 部署 → Agent API 暴露     │  ││
│  │  └─────────────────────────────────────────────────────┘  ││
│  └──────────────────────────────────────────────────────────┘│
└──────────────────────────────────────────────────────────────┘
```

## 技术栈

| 层级 | 技术选型 | 版本 |
|------|---------|------|
| 前端 | React + Next.js + TypeScript + Tailwind CSS | React 19 / Next 16 |
| 后端 | Go + Gin + GORM | Go 1.23 |
| 代码生成 | Python (DeepAgents SDK + FastAPI) | DeepAgents 0.5.5 |
| 数据库 | GreatSQL 8.0 (MySQL 兼容) | 8.0.32 |
| 对象存储 | MinIO | 2025-04-08 |
| 容器编排 | Kubernetes via Kind + agent-sandbox CRD | K8s 1.32 / Sandbox 0.4.3 |
| 镜像构建 | Docker CLI (Shell 调用) | — |

## 基础设施端口

| 服务 | 端口 | 用途 |
|------|------|------|
| Go 后端 | `8080` | REST API 服务 |
| Next.js 前端 | `3000` | Web UI |
| GreatSQL | `3307` | Agent 元数据持久化 |
| MinIO API | `9000` | 对象存储 |
| MinIO Console | `9001` | 存储管理面板 |
| Docker Registry | `5000` | 本地镜像仓库 |

## 快速开始

### 前置条件

- Go 1.23+, Node.js 20+, Python 3.11+
- Kind 集群已部署 (`kubectl cluster-info`)
- GreatSQL 已启动 (端口 3307)
- MinIO 可用 (端口 9000)
- agent-sandbox operator 已安装

### 启动后端

```bash
cd backend
go mod tidy
go run cmd/server/main.go
# 默认监听 :8080
```

### 启动前端

```bash
cd frontend
npm install
npm run dev
# 默认监听 :3000
```

### 环境变量

后端全量配置详见 [backend/AGENTS.md](backend/AGENTS.md)，核心变量：

```bash
export SERVER_PORT=8080
export MYSQL_DSN="agent_manager:Agent@Manager2026@tcp(127.0.0.1:3307)/agent_manager?charset=utf8mb4&parseTime=True&loc=Local"
export MINIO_ENDPOINT=127.0.0.1:9000
export MINIO_ACCESS_KEY=minioadmin
export MINIO_SECRET_KEY=minioadmin
export KUBECONFIG=~/.kube/config
export LOCAL_REGISTRY=localhost:5000
```

前端需设置：

```bash
export NEXT_PUBLIC_API_URL=http://localhost:8080/api/v1
```

### 代码生成独立验证

```bash
cd codegen
source venv/bin/activate
python generator.py test/test-config.json test/output/
```

## 项目结构

```
agent-manager/
├── backend/                     # Go 后端
│   ├── cmd/server/main.go       # 入口
│   ├── config/config.go         # 配置
│   └── internal/                # handler / service / model / k8s / docker / minio / codegen
├── frontend/                    # Next.js 前端
│   └── src/
│       ├── app/                 # App Router 页面
│       └── lib/api.ts           # API 客户端
├── codegen/                     # Python 代码生成
│   ├── generator.py             # 核心生成器
│   └── schema/                  # JSON Schema
├── sandbox/                     # agent-sandbox 部署 manifest
├── docs/                        # 环境部署 & 验证文档
├── PLAN.md                      # 完整项目计划
├── AGENTS.md                    # AI 开发指引 (含子模块索引)
└── package.json                 # 顶层 (截图工具)
```

## 文档索引

| 文档 | 说明 |
|------|------|
| [AGENTS.md](AGENTS.md) | AI 开发指引与子模块索引 |
| [PLAN.md](PLAN.md) | 完整项目执行计划 |
| [docs/deployment.md](docs/deployment.md) | 环境部署总文档 |
| [backend/AGENTS.md](backend/AGENTS.md) | Go 后端开发指南 |
| [frontend/AGENTS.md](frontend/AGENTS.md) | 前端开发指南 |
| [codegen/AGENTS.md](codegen/AGENTS.md) | 代码生成模块开发指南 |

## Agent 生命周期

```
draft → generated → built → deployed → published
                            ↑         ↓
                            └─ unpublished ←─┘
```

1. **创建**: 通过表单/JSON/YAML 配置 Agent
2. **生成代码**: Python codegen 生成 FastAPI + DeepAgents 微服务
3. **构建镜像**: Docker build → push 到本地 Registry
4. **部署**: 创建 K8s Sandbox CRD，Pod 自动调度运行
5. **发布/下线**: 控制流量可达性

## 注意事项

- 不要修改/删除 Dify 相关的数据库和 Bucket (`dify`, `dify_*`)
- API Key 等敏感信息通过环境变量注入，勿硬编码
- Kind 集群数据非持久化，重启后 Sandbox 资源需重建
- Docker Registry 对外使用 `172.20.0.1:5000` 供 K8s 拉取，宿主机用 `localhost:5000`

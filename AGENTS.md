# Agent Manager — AGENTS.md

## Project Overview

Agent Manager 是一个 **Agent 管理平台**，支持通过页面/JSON/YAML 配置 Agent，自动生成 DeepAgents 代码，打包为 Docker 镜像，部署到 Kubernetes 的 agent-sandbox 隔离环境中运行，并管理 Agent 的发布/下线生命周期。

### 技术栈

| 层级 | 技术选型 |
|------|---------|
| 前端 | React 19 + Next.js 16 + TypeScript + Tailwind CSS 3 |
| 后端 | Go 1.23 + Gin + GORM |
| 代码生成 | Python (DeepAgents SDK + FastAPI) |
| Agent 框架 | agent-framework (FastAPI + DeepAgents v0.6.1) |
| 数据库 | GreatSQL 8.0 (MySQL 兼容，端口 3307) |
| 对象存储 | MinIO (端口 9000/9001) |
| 容器编排 | Kubernetes (Kind 本地集群) + agent-sandbox CRD |
| 镜像构建 | Docker SDK (Shell 调用) |

### 核心组件版本

| 组件 | 版本 |
|------|------|
| agent-sandbox | kubernetes-sigs/agent-sandbox v0.4.3 |
| DeepAgents | langchain-ai/deepagents v0.6.1 |
| agent-framework | FastAPI + DeepAgents + MySQL Checkpoint |

---

## 基础规则

严格按用户需求执行，不擅自加功能、不脑补逻辑、不画蛇添足；只输出可直接运行的完整代码，拒绝伪代码。需求模糊主动提问，输出无多余闲聊，全程对齐项目现有代码风格、目录结构、命名规范。

## 开发流程

先看项目目录和现有关联代码，理清逻辑再编码；只修改指定文件与逻辑，不改动无关代码、不整文件重写。

## 代码规范

命名语义化，禁止硬编码密钥、魔法数字；网络、IO、数据库操作必做判空、边界校验和异常捕获；优先复用现有工具，不私自升级框架、乱加依赖；复杂逻辑加中文注释。

## 输出格式

代码块标语言、改文件标路径；保留配置原有缩进，不搞多余排版；完工自动清理调试日志、临时测试代码。

## 安全约束

不随意改 Git、Docker 及系统配置；禁用高危删除命令，敏感信息用占位符；不做删核心文件、清依赖等破坏性操作，环境报错先给排查方案。

---

## 项目目录结构

```
/root/agent-manager/
├── backend/                    # Go 后端 → backend/AGENTS.md
├── frontend/                   # Next.js 前端 → frontend/AGENTS.md
├── codegen/                    # Python 代码生成模块 → codegen/AGENTS.md
├── agent-framework/            # Agent Framework 独立服务 → agent-framework/AGENTS.md
├── e2e/                        # 端到端测试 → e2e/AGENTS.md
├── sandbox/                    # agent-sandbox 部署文件
├── docs/                       # 文档
│   └── troubleshooting/        # 问题排查文档
├── PLAND.md                    # 完整项目计划
└── package.json                # 顶层 (puppeteer 截图工具)
```

---

## 基础设施

| 服务 | 端口 | 用途 |
|------|------|------|
| Nginx 反向代理 | 8911 | 统一入口 (前端/后端/K8s Ingress) |
| Go 后端 | 8080 | REST API 服务 |
| Next.js 前端 | 3000 | Web UI (PM2 standalone) |
| GreatSQL (MySQL) | 3307 | 持久化 Agent 元数据 |
| MinIO API | 9000 | 对象存储 |
| MinIO Console | 9001 | 对象存储管理面板 |
| Docker Registry | 5001 | 本地镜像仓库 (HTTP) |
| K8s Ingress Controller | 30080/30443 | K8s 集群入口 |
| Kind K8s | — | 本地 Kubernetes 集群 |

### Nginx 反向代理架构

```
浏览器 :8911
    │
    ▼
┌──────────┐
│  Nginx   │  /etc/nginx/conf.d/agent-manager.conf
│  :8911   │
└──────────┘
  │          │            │
  │ /        │ /api/      │ /agent/
  ▼ :3000    ▼ :8080      ▼ :30080
Frontend    Backend       K8s Ingress
(Next.js)   (Go/Gin)      Controller
                           │
                           ▼ agent-{id}-svc:8100
                        Agent Pod (agent-framework)
```

---

## 新增功能

### 1. 运行模式

Agent 支持两种运行模式：

| 模式 | 说明 | 流程 |
|------|------|------|
| **构建模式** (build) | 传统模式，生成代码并构建镜像 | 配置 → 代码生成 → 镜像构建 → K8s 部署 |
| **挂载模式** (mount) | 使用预构建镜像，配置挂载部署 | 配置 → 选择镜像 → MinIO 存储 → ConfigMap 挂载 → K8s 部署 |

**挂载模式优势：**
- 无需每次构建镜像，部署速度更快
- 使用预构建的 agent-framework 镜像
- 配置通过 ConfigMap 挂载到容器
- 支持独立的 Checkpoint 数据库配置

**挂载模式部署流程：**
1. 用户创建 Agent，选择挂载模式和镜像
2. 配置存储到 MinIO (AGENTS.md + skills + mcp-configs)
3. 部署时创建 ConfigMap (配置) 和 Secret (LLM API Key)
4. K8s Sandbox 挂载 ConfigMap 到 /config 目录
5. agent-framework 从 /config 读取配置运行

**新增环境变量：**

敏感配置存放在 `.env.secrets`（已加入 .gitignore），具体变量如下：

| 变量 | 说明 | 示例格式 |
|------|------|---------|
| `LLM_API_KEY` | LLM API 密钥 | 见 .env.secrets |
| `LLM_MODEL` | LLM 模型 ID | 见 .env.secrets |
| `LLM_ENDPOINT` | LLM API 端点 | 见 .env.secrets |
| `DEFAULT_CHECKPOINT_DSN` | Checkpoint 数据库 DSN | 见 .env.secrets |
| `AVAILABLE_IMAGES` | 可选镜像列表 | `agent-framework:latest\|Agent Framework v0.5.5` |
| `DEFAULT_IMAGE` | 默认镜像 | `agent-framework:latest` |

```bash
# 从 .env.secrets 加载敏感配置
source .env.secrets
```

**挂载模式关键实现细节：**
- 镜像自动补全 Registry 前缀：短镜像名 (如 `agent-framework:latest`) 自动补充为 `{registry}/agent-framework:latest`
- `Publish` 挂载模式始终调用 `DeployWithMount()`，非 `Deploy()` (修复重发布 ImagePullBackOff)
- Checkpoint DSN 主机须用 `172.20.0.1` (Docker 网关)，因 K8s Pod 内 `127.0.0.1` 指向 Pod 自身
- Agent 容器端口 **8100**，构建模式端口 8000
- `INGRESS_HOST` 环境变量决定对外地址展示 (默认 `localhost`，须设为 nginx 入口地址)
- LLM 配置通过后端环境变量注入：`LLM_API_KEY`/`LLM_MODEL`/`LLM_ENDPOINT` → Pod 内 `LLM_API_KEY`(Secret)/`LLM_MODEL_ID`/`LLM_BASE_URL`/`LLM_PROVIDER`(hardcoded=ctyun)

### 2. Agent 删除功能

删除 Agent 时自动清理所有相关资源：

| 资源类型 | 清理逻辑 |
|---------|---------|
| K8s | Ingress → Service → Sandbox CRD + ConfigMap + Secret (挂载模式) |
| Docker | 本地镜像 + 远程仓库镜像 |
| MinIO | 代码文件 (agents/{id}/*) |
| MySQL | Agent 记录 (CASCADE 删除子表) |

**删除策略（按状态）：**
- `draft`: 仅删除数据库
- `generated`: MinIO + 数据库
- `built`: Docker + MinIO + 数据库
- `deployed/published`: K8s + Docker + MinIO + 数据库
- `error`: 尝试清理所有可能资源

### 3. 基础镜像构建

预构建基础镜像 `agent-base:latest`，包含所有 pip 依赖，加速 Agent 镜像构建：

| 场景 | 构建时间 |
|------|---------|
| 首次构建（构建基础镜像） | ~60s |
| 后续构建（使用缓存） | ~5s |

**实现方式：**
1. 启动时检查基础镜像是否存在，不存在则自动构建
2. 代码生成时 Dockerfile 替换 `FROM python:3.12-slim` 为 `FROM {registry}/agent-base:latest`
3. 构建时跳过 `pip install`，仅复制 `agent.py`

---

## 启动服务

后端通过 Makefile 启动，LLM/Checkpoint 等必要配置通过环境变量注入（`config.go` 无默认值，必须外部提供）：

```bash
# 后台运行
make backend-start    # 构建 + 启动后端 :8080

# 开发模式
make dev-backend      # go run 热重载

# 前端
make frontend-start   # 构建 + PM2 启动 :3000
make frontend-restart # 构建 + 重启

# 重启
make backend-restart make frontend-restart
```

实际环境变量值定义在 `Makefile` 的 `backend-start` 和 `dev-backend` 目标中。

---

## 子模块 AGENTS.md 索引

- [backend/AGENTS.md](backend/AGENTS.md) — Go 后端: 启动流程、配置、数据模型、API 路由、业务逻辑、基础设施客户端
- [codegen/AGENTS.md](codegen/AGENTS.md) — Python 代码生成模块: 核心函数、调用模式、生成产物、JSON Schema、已知问题
- [frontend/AGENTS.md](frontend/AGENTS.md) — Next.js 前端: 页面路由、API 客户端、状态管理、组件架构、页面详情
- [e2e/AGENTS.md](e2e/AGENTS.md) — 端到端测试: Puppeteer 脚本、测试报告、截图管理

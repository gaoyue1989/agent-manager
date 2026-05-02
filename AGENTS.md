# Agent Manager — AGENTS.md

## Project Overview

Agent Manager 是一个 **Agent 管理平台**，支持通过页面/JSON/YAML 配置 Agent，自动生成 DeepAgents 代码，打包为 Docker 镜像，部署到 Kubernetes 的 agent-sandbox 隔离环境中运行，并管理 Agent 的发布/下线生命周期。

### 技术栈

| 层级 | 技术选型 |
|------|---------|
| 前端 | React 19 + Next.js 16 + TypeScript + Tailwind CSS 3 |
| 后端 | Go 1.23 + Gin + GORM |
| 代码生成 | Python (DeepAgents SDK + FastAPI) |
| 数据库 | GreatSQL 8.0 (MySQL 兼容，端口 3307) |
| 对象存储 | MinIO (端口 9000/9001) |
| 容器编排 | Kubernetes (Kind 本地集群) + agent-sandbox CRD |
| 镜像构建 | Docker SDK (Shell 调用) |

### 核心组件版本

| 组件 | 版本 |
|------|------|
| agent-sandbox | kubernetes-sigs/agent-sandbox v0.4.3 |
| DeepAgents | langchain-ai/deepagents v0.5.5 |

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
├── e2e/                        # 端到端测试 → e2e/AGENTS.md
├── sandbox/                    # agent-sandbox 部署文件
├── docs/                       # 文档
├── PLAND.md                    # 完整项目计划
└── package.json                # 顶层 (puppeteer 截图工具)
```

---

## 基础设施

| 服务 | 端口 | 用途 |
|------|------|------|
| Go 后端 | 8080 | REST API 服务 |
| Next.js 前端 | 3000 | Web UI |
| GreatSQL (MySQL) | 3307 | 持久化 Agent 元数据 |
| MinIO API | 9000 | 对象存储 |
| MinIO Console | 9001 | 对象存储管理面板 |
| Docker Registry | 5000 | 本地镜像仓库 |
| Kind K8s | — | 本地 Kubernetes 集群 |

---

## 新增功能

### 1. Agent 删除功能

删除 Agent 时自动清理所有相关资源：

| 资源类型 | 清理逻辑 |
|---------|---------|
| K8s | Ingress → Service → Sandbox CRD |
| Docker | 本地镜像 + 远程仓库镜像 |
| MinIO | 代码文件 (agents/{id}/*) |
| MySQL | Agent 记录 (CASCADE 删除子表) |

**删除策略（按状态）：**
- `draft`: 仅删除数据库
- `generated`: MinIO + 数据库
- `built`: Docker + MinIO + 数据库
- `deployed/published`: K8s + Docker + MinIO + 数据库
- `error`: 尝试清理所有可能资源

### 2. 基础镜像构建

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

## 子模块 AGENTS.md 索引

- [backend/AGENTS.md](backend/AGENTS.md) — Go 后端: 启动流程、配置、数据模型、API 路由、业务逻辑、基础设施客户端
- [codegen/AGENTS.md](codegen/AGENTS.md) — Python 代码生成模块: 核心函数、调用模式、生成产物、JSON Schema、已知问题
- [frontend/AGENTS.md](frontend/AGENTS.md) — Next.js 前端: 页面路由、API 客户端、状态管理、组件架构、页面详情
- [e2e/AGENTS.md](e2e/AGENTS.md) — 端到端测试: Puppeteer 脚本、测试报告、截图管理

# OAF 服务发布平台 — AGENTS.md

## Project Overview

OAF 服务发布平台（v2）：上传符合规范的 **OAF 配置包**，经 K8s 原生 Deployment+Service+Ingress 拉起服务（包 PVC subPath 只读挂载、环境变量 ConfigMap envFrom），就绪后自动调用 A2A `/.well-known/agent-card.json` 注册服务信息；提供列表/状态/重新发布/下线/删除。核心能力同进程暴露为 MCP 服务，智能发布助手（release-agent）通过 MCP 对话式驱动全流程。

### 技术栈

| 层 | 选型 |
|----|------|
| 管理后端 | Go 1.23 + Gin + GORM + client-go（typed，InClusterConfig） |
| MCP | modelcontextprotocol/go-sdk v1.3.1（streamableHttp /mcp，与 REST 同进程） |
| 前端 | Next.js 16 + React 19 + Tailwind（容器化 standalone） |
| 业务运行时 | agent-framework（Java/Spring Boot :8100，AgentScope Harness） |
| 元数据 | 集群内 MySQL 8.0（oaf_platform / oaf_checkpoint） |
| 编排 | Kind 单节点 K8s 1.32 + ingress-nginx；共享 PVC platform-data |

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
├── backend/            # Go 管理后端（REST /api/v1 + MCP /mcp 同进程）→ backend/AGENTS.md
├── frontend/           # Next.js 前端（服务列表/发布向导/详情/发布助手对话）
├── agent-framework/    # 业务 Agent 运行时（Java）→ agent-framework/AGENTS.md
├── release-agent/      # 智能发布助手 OAF 包源文件
├── manifests/          # 平台自举清单（platform/platform-ingress/frontend.yaml）
├── e2e/                # 全流程回归脚本 → e2e 内 package.json
├── docs/               # 部署指南(deployment.md) + 规范参考(oaf-specification.md) + 排障(troubleshooting/)
└── REDESIGN.md         # 重构设计文档（权威，含实施记录附录 B）
```

---

## 基础设施

| 组件 | 地址 | 用途 |
|------|------|------|
| 宿主机 nginx | :8911 | 统一入口（前端/API/MCP/业务 Agent） |
| platform-backend | svc:8080 / NodePort 30880 | REST + MCP |
| platform-frontend | NodePort 30881 | Web UI |
| ingress-nginx | NodePort 30080 | 业务 Agent（/agent/{name}） |
| oaf-mysql | svc:3306 | 平台元数据 + checkpoint |
| 共享 PVC | platform-data (10Gi) | OAF 包存储：`packages/{id}` subPath 只读挂 /config |
| namespace | agent-platform | 全部平台与业务资源 |

关键约定：
- 业务 Pod 固定注入 `AGENT_CONFIG_DIR=/config`、`AGENT_WORKSPACE_DIR=/workspace`、`SERVER_HOST/SERVER_PORT`（均为保留键，用户 env 冲突即 400）
- env 为**全量覆盖**语义（PATCH /services/:id/env），上限 64 键 × 32KB
- 业务 Ingress 注入 proxy-read/send-timeout=3600 与 x-forwarded-prefix
- 敏感配置在 `.env.secrets`（gitignored）

---

## 启动服务

```bash
# 单测 / 本地后端 / 前端热更
cd backend && make test && make run-dev
cd frontend && npm run dev

# 镜像构建 → 导入集群 → 部署（全流程见 docs/deployment.md）
cd backend && make image && make kind-load && kubectl apply -f ../manifests/*.yaml

# 更新镜像后的固定动作：rollout restart 对应 deployment
kubectl -n agent-platform rollout restart deployment/platform-backend   # Ingress 注解由它下发
```

---

## CI（GitHub Actions，推送到 master 触发）

推送 master 后 3 个工作流并行：**先单测，通过后构建镜像推送 Docker Hub**（凭据 `DOCKERHUB_USERNAME`/`DOCKERHUB_TOKEN`，GitHub 仓库 Secrets）。

| 工作流 | 单测 | 镜像推送 |
|--------|------|---------|
| backend-ci | `go vet ./...` + `go test ./...` | `gaoyue1989/agent-manager-backend:{latest, <short-sha>}` |
| frontend-ci | `npm run lint` + `npm run build` | `gaoyue1989/agent-manager-frontend:{latest, <short-sha>}` |
| agent-framework-ci | `mvn test`（455 用例，Maven Central 依赖） | `gaoyue1989/agent-framework:agentscope-{maven 版本}-v{YYYYMMDD}`（如 agentscope-2.1.0-v20260907） |

细节：
- 镜像构建用 buildx + gha 缓存；agent-framework 构建前自动下载 OTel Java Agent（jar 不入库，版本取 Makefile `OTEL_JAVAAGENT_VERSION`）
- agent-framework 镜像 tag 由 pom `project.version` 动态派生（`mvn help:evaluate`），日期取 UTC
- 业务镜像更新到 kind 集群仍是手动流程（`docker save | ctr import` → rollout），CI 只负责测试与镜像分发

---

## 子模块 AGENTS.md 索引

- [backend/AGENTS.md](backend/AGENTS.md) — Go 后端：目录结构、REST/MCP 契约、状态机、K8s 对象构造、安全限制
- [agent-framework/AGENTS.md](agent-framework/AGENTS.md) — Java 运行时：A2A/单次流端点、MCP 注册（fail-soft/read_only/HITL）、环境变量、Debug Console

# OAF 服务发布平台 — AGENTS.md

## Project Overview

OAF 服务发布平台（v2）：上传符合规范的 **OAF 配置包**，经 K8s 原生 Deployment+Service+Ingress 拉起服务（包 PVC subPath 只读挂载、环境变量 ConfigMap envFrom），就绪后自动调用 A2A `/.well-known/agent-card.json` 注册服务信息；提供列表/状态/重新发布/下线/删除。核心能力同进程暴露为 MCP 服务，智能发布助手（release-agent）通过 MCP 对话式驱动全流程。

### 技术栈

| 层 | 选型 |
|----|------|
| 管理后端 | Go 1.26 + Gin + GORM + client-go（typed，InClusterConfig） |
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
└── docs/               # 部署指南(deployment.md) + 规范参考(oaf-specification.md) + 设计归档(design/，索引见 design/README.md)
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
| oaf-redis | svc:6379 | session_event 事件流存储（Redis Streams，支撑 durable SSE 多副本续传） |
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

## CI（GitHub Actions）与日常提交流程

### 触发规则（按目录过滤，加速无关变更）

- **push 仅 master**（跑测试 + 发布镜像）；**其余分支只在 PR 时触发**（避免双事件重复跑，门禁只认 PR run）
- **concurrency 组含 workflow 维度**（`ci-${{ github.workflow }}-<分支名>`，PR #10）：同工作流内同分支新旧 run 互斥（双事件仍只保留最新一次），**跨工作流互不取消**——修复前共享组名导致 master push 三工作流互相取消（无关 job 显示 cancelled 而非 skipped、镜像推送可能被静默取消丢失）
- **PR / master push** 都会启动三个工作流，但 **job 按改动目录过滤**：
  - `backend/**` → backend-ci 的单测
  - `frontend/**` → frontend-ci 的单测
  - `agent-framework/**` → agent-framework-ci 的单测 + 三个 E2E
  - 根目录工作流/脚本变更（`.github/**`）→ 三个工作流全跑
  - **不相关目录的 job 显示 Skipped，门禁视为通过**——这是刻意设计：PR 上工作流必须照常触发，否则必需检查会 pending 卡死 PR；不能在工作流层面用 `paths` 过滤
- 触发映射：`.github/workflows/<name>.yml` 自身变更也会触发对应工作流（保证 CI 配置改动被验证）
- master push 的测试 job 同样按目录过滤（无关目录 Skipped），镜像推送仅在相关目录有变更时执行
- 跨目录改动（如前后端联动）→ 多个工作流并行各自执行，互不干扰

| 工作流 | 单测 | E2E | 镜像推送（仅 master push） |
|--------|------|-----|--------------------------|
| backend-ci | `go vet ./...` + `go test ./...` | — | `gaoyue1989/agent-manager-backend:{latest, <short-sha>}` |
| frontend-ci | `npm run lint` + `npm run build` | — | `gaoyue1989/agent-manager-frontend:{latest, <short-sha>}` |
| agent-framework-ci | `mvn test`（83 类 / 883 个 @Test，实跑 860 用例）+ `eval-selftest`（评测飞轮离线自检） | 核心/多副本/沙箱/工具插件四 job（见 `agent-framework/docs/e2e-ci-plan.md`） | `gaoyue1989/agent-framework:agentscope-{maven 版本}-v{YYYYMMDD}`（如 agentscope-2.1.0-v20260907） |

### 日常提交流程（必须走 PR 门禁）

```bash
# 1. 从最新 master 切 feature 分支
git checkout master && git pull
git checkout -b feat/xxx        # 命名：feat/ | fix/ | chore/ | docs/

# 2. 开发提交（推分支即触发 CI 验证，尽早暴露问题）
git push origin feat/xxx

# 3. 开 PR 到 master（GitHub 网页或 gh pr create）
#    → 必需检查自动执行：
#      单测 (mvn test) / 单测 (go vet + go test) / 单测 (lint + build)
#      E2E 核心（API+UI）/ E2E 多副本（R 组+U9）/ E2E 沙箱（mock OpenSandbox）
#    另有两个非必需 job 同步跑（红只告警不挡合并，但应一并修）：
#      E2E 工具插件（SPI+三态权限）/ 评测自检（flywheel selftest）

# 4. 全绿后合并（网页 Merge 按钮）
#    红了就修：提交会自动重跑检查
```

### 分支保护（master，强制）

- **六项必需状态检查**：上表三个单测 + agent-framework 三个 E2E job；未全绿合并请求被拒（`blocked`）
- **新增 job 默认不是必需检查**：`E2E 工具插件` 与 `评测自检` 目前只跑不挡合并；要转必需需仓库管理员在分支保护里加勾（`gh api -X PATCH repos/:owner/:repo/branches/master/protection/required_status_checks`）
- **strict**：合并前必须基于最新 master（过期需 rebase/update branch 重跑）
- **enforce_admins**：管理员同样受限——**对 master 的直接 push 被拒绝**（`protected branch hook declined`），一切变更走 PR
- **禁止 force push / 删除分支**
- 临时提交不慎直接落在本地 master：切到新分支提 PR（`git checkout -b chore/xxx && git push`），不要绕过门禁

细节：
- 镜像构建用 buildx + gha 缓存；agent-framework 构建前自动下载 OTel Java Agent（jar 不入库，版本取 Makefile `OTEL_JAVAAGENT_VERSION`）
- agent-framework 镜像 tag 由 pom `project.version` 动态派生（`mvn help:evaluate`），日期取 UTC
- PR 与 feature 分支的 CI 只做测试验证，不推镜像（`build-push` 有 `if: master push` 门控）
- 业务镜像更新到 kind 集群仍是手动流程（`docker save | ctr import` → rollout），CI 只负责测试与镜像分发

---

## 子模块 AGENTS.md 索引

- [backend/AGENTS.md](backend/AGENTS.md) — Go 后端：目录结构、REST/MCP 契约、状态机、K8s 对象构造、安全限制
- [agent-framework/AGENTS.md](agent-framework/AGENTS.md) — Java 运行时：A2A/单次流端点、MCP 注册（fail-soft/read_only/HITL）、环境变量、Debug Console

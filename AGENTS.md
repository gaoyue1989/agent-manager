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

## 工程规范

通用工程原则与开发前/中/后行为规范（长期主义、SSOT、TDD、worktree 工作流、Commit 携带对话 id、文档命名等）见 [Agents.custome.md](Agents.custome.md)，并行生效。本节仅列项目专属约束：

- 严格按用户需求执行，不擅自加功能、不脑补逻辑、不画蛇添足；需求模糊主动提问，输出无多余闲聊，全程对齐项目现有代码风格、目录结构、命名规范
- 只输出可直接运行的完整代码，拒绝伪代码；代码块标语言、改文件标路径，保留配置原有缩进
- 命名语义化，禁止魔法数字；网络、IO、数据库操作必做判空、边界校验和异常捕获
- 禁止编写注释，存量注释不主动清理
- 禁止硬编码密钥，敏感信息用占位符（敏感配置在 `.env.secrets`，gitignored）
- 依赖口径：优先复用项目内现有实现，无则引入成熟第三方库，禁止自造轮子；框架版本升级需先确认
- 不随意改 Git、Docker 及系统配置；禁用高危删除命令与破坏性操作（删核心文件、清依赖等）；环境报错先给排查方案
- 「不做兼容」仅约束新代码的设计取向；既有兼容性架构决策（存量服务 env 固化不自动迁移、AUTH_TOKEN 语义保留、Flyway baseline-on-migrate）为刻意设计，不追溯推翻

### 数据库演进（Flyway，红线）

**agent-framework 的表结构/数据演进一律新增 Flyway 迁移文件**（`agent-framework/src/main/resources/db/migration/V<N>__<描述>.sql`，启动自动执行、存量库自动基线），**禁止**在 Store 构造器或请求路径里手工 DDL（该模式已移除），禁止修改已合并的 V 文件（checksum 校验会拒绝启动）；"新机制只写新数据"的改动必须同版本配套存量回填迁移（见 docs/design/db-migration-flyway-design.md §7）。

---

## 架构强约束（控制面/业务面分层，强制）

两条平台级设计原则，任何改动不得违反；评审时按此清单逐条核对（细则见 [backend/AGENTS.md](backend/AGENTS.md) 与 [agent-framework/AGENTS.md](agent-framework/AGENTS.md)）：

**原则一：backend 是控制面——重启/升级/扩缩容不得影响任何已发布业务 agent 服务**

- 业务 K8s 资源（Deployment/Service/Ingress/CM/Secret）只允许由显式发布 API（REST/MCP）写入；启动路径禁止任何 reconcile/sync-all/后台批量迁移
- 禁止 ownerReferences/finalizer 把业务资源生命周期绑到平台自身对象；禁止后台定时任务写业务资源
- 业务运行时不得依赖 backend 可用性：env 经 K8s CM/Secret envFrom 常驻集群、OAF 包经 PVC 只读直挂、流量经 Ingress 直连 `{name}-svc`，三条链路都不经过 backend 进程
- backend 按单副本设计（manifests/platform.yaml）；扩多副本前必须先解决发布链路一致性（同名发布竞态、asyncWaitAndRegister 重复推进），且任何平台内部故障/竞态都不得波及业务面

**原则二：agent-framework 是业务面——服务间数据天然隔离 + 每 Pod 无状态可横向扩展**

- 影响正确性的状态只允许存共享存储（MySQL checkpoint / Redis）；Pod 本地（内存、emptyDir）只允许可丢弃/可重建的缓存
- 禁止 Service `sessionAffinity`；任意请求必须可落任意副本（`/threads/{sid}/subscribe`、`/status`、confirm 等全部基于共享存储裁决）；e2e-multi / e2e-protocol-multi 双副本门禁必须保持
- 多服务共享同一 MySQL/Redis 时，隔离靠 per-service checkpoint 库名与 Redis 前缀，表结构/Redis key 无 agent 维度，缺省即共享（`session_user` 跨服务串列、`sbx:guard` 跨服务互锁）。Publish 新建服务时平台自动派生这两键（issue #142：`CHECKPOINT_JDBC_URL` 改写 `{原库}_{k8s 名}` + 自动建库、`AGENT_REDIS_PREFIX={k8s 名}:`，用户显式值优先）；存量服务 env 已固化不自动迁移，收敛需显式改 env 后 republish

---

## 项目目录结构

```
/root/agent-manager/
├── backend/            # Go 管理后端（REST /api/v1 + MCP /mcp 同进程）→ backend/AGENTS.md
├── frontend/           # Next.js 控制台（JWT 登录/用户侧工作空间/运维侧治理/发布助手对话）
├── agent-framework/    # 业务 Agent 运行时（Java）→ agent-framework/AGENTS.md
├── release-agent/      # 智能发布助手 OAF 包源文件
├── manifests/          # 平台自举清单（platform/platform-router/platform-ingress/frontend.yaml + ci/ CI overlay）
├── charts/             # Helm Charts：oaf-platform（离线部署）+ oaf-agent（跨集群导出交付）
├── e2e/                # 全流程回归脚本（console-split 按模块套件 + 存量 A~E 场景）→ e2e 内 package.json
├── demo/               # order-fulfillment 多 Agent 协同演示（Agent Protocol 远程子 agent）
├── scripts/            # 辅助脚本（每日更新日志 gen-daily-changelog.sh）
└── docs/               # 部署指南(deployment.md) + 规范参考(oaf-specification.md) + 设计归档(design/，索引见 design/README.md) + 实施记录(implementation/)
```

---

## 基础设施

| 组件 | 地址 | 用途 |
|------|------|------|
| 宿主机 nginx | :8911 | 统一入口（前端/API/MCP/业务 Agent） |
| platform-backend | svc:8080 / NodePort 30880 | REST + MCP |
| platform-frontend | NodePort 30881 | Web UI |
| ingress-nginx | NodePort 30080 | 业务 Agent 入口（router 模式：共享 Ingress /agent → platform-router；host 模式：per-service 域名） |
| platform-router | svc:80（nginx 1.27.1 ×2） | router 模式子路径路由器（manifests/platform-router.yaml 自举，静态泛化配置、发布链路零写入） |
| oaf-mysql | svc:3306 | 平台元数据 + checkpoint |
| oaf-redis | svc:6379 | session_event 事件流存储（Redis Streams，支撑 durable SSE 多副本续传） |
| 共享 PVC | platform-data (10Gi) | OAF 包存储：`packages/{id}` subPath 只读挂 /config |
| namespace | agent-platform | 全部平台与业务资源 |

细节约定的真相源在 [backend/AGENTS.md](backend/AGENTS.md)，此处仅索引：

- 业务 Pod 保留键（AGENT_CONFIG_DIR 等）、env 全量覆盖语义（64 键 × 32KB）、敏感键自动路由服务 Secret
- 平台默认配置仅作发布/编辑 env 时的表单默认填入，不经运行时注入（docs/design/platform-default-config-secret-design.md）
- 业务路由双模式（`INGRESS_HOST_SUFFIX` 唯一开关：router 子路径 / host 域名；docs/design/subpath-routing-design.md、ingress-host-mode-design.md）
- 控制台三面（auth/console/admin）与数据面网关鉴权 `ROUTER_AUTH_MODE`（docs/design/console-user-workspace-split-design.md）

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

## CI 与提交流程

CI 配置真相源在 [.github/workflows/](.github/workflows/)（backend-ci / frontend-ci / agent-framework-ci / charts-ci / platform-e2e-ci）；镜像构建与 tag 派生见各模块 Makefile。核心不变量：

- **push 仅 master** 出镜像；其余分支只在 PR 时触发，PR run 是唯一门禁口径
- **job 按改动目录过滤**，不相关目录显示 Skipped、门禁视为通过——刻意设计（PR 上工作流必须照常触发，否则必需检查 pending 卡死 PR），禁止在工作流层面用 `paths` 过滤
- **concurrency 组含 workflow 维度**（同工作流内同分支互斥、跨工作流互不取消），勿回退为共享组名（曾致 master push 镜像推送被静默取消）
- **分支保护**：strict（合并前基于最新 master）+ enforce_admins（对 master 直接 push 被拒）+ 禁 force push/删分支，一切变更走 PR
- **九项必需检查**：三个单测（mvn test / go vet+go test / lint+build）+ agent-framework 四 E2E（核心/多副本/沙箱/协议多副本 P 组）+ `E2E 平台（kind + 控制台全功能）` + `Chart 校验 (helm lint + template)`
- 新增 job 默认非必需，转必需需管理员在分支保护加勾（job name 与保护规则字符串须完全一致）；`E2E 沙箱生命周期（L 组）` 转必需前须满足观察期（sandbox-pool-opensandbox-design §9.5 十项预案）

日常提交流程（worktree 工作流，通用规则见 [Agents.custome.md](Agents.custome.md)）：

```bash
# 0. 主工作区保持在 master，拉取远程
git checkout master && git pull

# 1. .tmp 下开 worktree + feature 分支（命名：feat/ | fix/ | chore/ | docs/）
git worktree add .tmp/<名称> -b docs/xxx master
cd .tmp/<名称>        # 开发；推分支即触发 CI 验证，尽早暴露问题

# 2. 开 PR 到 master（gh pr create 或网页）→ 必需检查全绿后合并
# 3. 合并后清理
git worktree remove .tmp/<名称> && git branch -d docs/xxx
```

- 临时提交不慎落在本地 master：切新分支提 PR（`git checkout -b chore/xxx && git push`），不要绕过门禁
- AI 生成的提交：conventional commit + 尾行 `Chat-Session: <对话id>` trailer
- 业务镜像更新到 kind 集群是手动流程（`docker save | ctr import` → rollout），CI 只负责测试与镜像分发

---

## 文档规范

- 新建文档命名 `文档名-YYMMDD.md`，每更新即改日期（通用规则见 [Agents.custome.md](Agents.custome.md)）；存量文档不重命名，AGENTS.md / README.md 等工具入口名豁免
- 文档只留索引与唯一信息：不复述代码/设计文档已有的逻辑，一律指向源，防止文档与代码形成两套信息源
- 设计文档归档与状态约定见 [docs/design/README.md](docs/design/README.md)（新增设计须在其清单补行）；部署指南 docs/deployment.md、OAF 规范 docs/oaf-specification.md、实施记录 docs/implementation/

---

## 子模块 AGENTS.md 索引

- [backend/AGENTS.md](backend/AGENTS.md) — Go 后端：目录结构、REST/MCP 契约、状态机、K8s 对象构造、安全限制
- [agent-framework/AGENTS.md](agent-framework/AGENTS.md) — Java 运行时：A2A/单次流端点、MCP 注册（fail-soft/read_only/HITL）、环境变量、Debug Console

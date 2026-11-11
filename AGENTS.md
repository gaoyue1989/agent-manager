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

## 工程原则

- 做长期正确的事，做长期正确的事，做长期正确的事
- 长期主义，面向维护，新增改动应降低未来开发成本，禁止引入技术债
- 警惕可能产生架构分叉的编码，按最佳实践来做统一的结构
- 禁止做掩耳盗铃的事情，我们必须真实的做事
- 单一真相源（SSOT），写入口唯一，其他皆派生、复用
- 遵循可执行最佳、现代的实践
- 写代码前思考这个代码是否有必须写，积极引入成熟第三方库解决问题，而不是自己造轮子
- 优先阅读代码而非写代码，优先寻找项目内可用可扩展的代码
- 迭代修改而非增量修改，重写/重构后删旧代码
- 遵循 KISS 原则，选最短且长期最佳实现路径，架构导致冗余就简化架构
- 做合适程度的强类型，相同的类型也单一类型定义源
- 不需计算编码成本，AI 编码不是人工，永远选择长期最佳的方案
- 做深度封装的组件，将复杂度封装在组件内，只暴露很少的必要的生命周期和 API，要替调用方着想
- 结构性问题用重构解决，精准手术式的修正，禁止使用过度兜底、补丁、特例来修正问题，少做短期主义的 hack 和补丁
- 除非特别要求，不做兼容，不保留历史包袱，面向长期干净整洁，减少复杂性

## 行为规范

### 开发前

- 先看代码，再询问信息和需求，禁止直接开发
- 问完信息后设计方案，并等待人类确认后再开发
- 尽量采用强类型、面向对象设计，善用函数作为补充
- 遵守数据库设计规范
- 前端减少描述性文本和边框线
- 开发前禁止运行全量测试，缩短开发时间
- 使用 `.tmp/docs` 建立临时工作文档（设计和流程规划等），用完即弃
- 本地主工作区保持在 master 分支，其他分支、worktree 在 `.tmp/` 下建立
- 拉取远程代码，开新 worktree、新分支开发

### 开发中

- 采用 TDD：先编写测试再实现功能，保证模块可进行有效单元测试，单元测试要涉及到下一层依赖，更深层的可 mock
- 完成模块开发后进行真实环境测试，使用真实资源模拟真实用户测试
- 单文件超出 2000 行必须进行结构审查，存在架构问题则按职责、层次、领域边界合理拆分
- 禁止编写注释，存量注释不主动清理
- 及时删除无效代码，发现不符合原则或规范的代码顺手优化
- 修复 Bug 禁止过度兜底，定位根因，用正确的逻辑解决问题
- 不编写只有单一调用、没有复用或抽象价值的私有 Helper 函数
- 开发中不跑全量测试，缩短开发时间
- 保持代码始终可编译、可运行
- 文档名字携带修改日期，以 `文档名-YYMMDD.md` 格式命名，每更新文档便更新日期

### 开发后

- 积极、频繁地进行 Git Commit：每完成一个独立、可回滚的变更，通过相关验证后立即提交
- 每次 Commit 都必须保证代码可编译、可运行
- Commit 消息中要携带对话 id
- 除非要部署上线，不运行全量测试，缩短开发时间
- 功能一切正常后再审一遍代码：是否有架构分叉、职责不清、历史包袱、过度实现和过度兜底；无抽象意义、无复用价值的 helper 合并掉；去掉无用的修改说明，保持长期干净清晰的架构
- 做启动测试、端到端测试
- 功能完成并验证、自查后，主动推送并创建或更新 PR，提供链接后等待负责人审核合并
- 合并后，删开发 worktree 和分支

## 项目规范

- 严格按用户需求执行，不擅自加功能、不脑补逻辑、不画蛇添足；需求模糊主动提问，输出无多余闲聊，全程对齐项目现有代码风格、目录结构、命名规范
- 只输出可直接运行的完整代码，拒绝伪代码；代码块标语言、改文件标路径，保留配置原有缩进
- 命名语义化，禁止魔法数字；网络、IO、数据库操作必做判空、边界校验和异常捕获
- 禁止硬编码密钥，敏感信息用占位符（敏感配置在 `.env.secrets`，gitignored）
- 依赖与框架版本：优先复用项目内现有实现，无则引入成熟第三方库；框架版本升级需先确认
- 「不做兼容」仅约束新代码的设计取向；既有兼容性架构决策（存量服务 env 固化不自动迁移、AUTH_TOKEN 语义保留、Flyway baseline-on-migrate）为刻意设计，不追溯推翻
- 不随意改 Git、Docker 及系统配置；禁用高危删除命令与破坏性操作（删核心文件、清依赖等）；环境报错先给排查方案

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
- 多服务共享同一 MySQL/Redis 时，隔离靠 per-service checkpoint 库名与 Redis 前缀，表结构/Redis key 无 agent 维度，缺省即共享（`session_user` 跨服务串列、`sbx:guard` 跨服务互锁）；Publish 自动派生隔离键（issue #142，用户显式值优先，存量不自动迁移）——派生细节与前置授权见 docs/deployment.md 运维备忘 4、agent-framework/AGENTS.md

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

对外入口与集群内组件地址、凭据见 [docs/deployment.md](docs/deployment.md)「关键端点」「凭据与敏感配置」（宿主 nginx :8911 统一入口、ingress-nginx 30080、前端 30881、oaf-mysql/oaf-redis、共享 PVC platform-data、namespace agent-platform）。

细节约定的真相源在 [backend/AGENTS.md](backend/AGENTS.md)，此处仅索引：

- 业务 Pod 保留键（AGENT_CONFIG_DIR 等）、env 全量覆盖语义（64 键 × 32KB）、敏感键自动路由服务 Secret
- 平台默认配置仅作发布/编辑 env 时的表单默认填入，不经运行时注入（docs/design/platform-default-config-secret-design.md）
- 业务路由双模式（`INGRESS_HOST_SUFFIX` 唯一开关：router 子路径 / host 域名；docs/design/subpath-routing-design.md、ingress-host-mode-design.md）
- 控制台三面（auth/console/admin）与数据面网关鉴权 `ROUTER_AUTH_MODE`（docs/design/console-user-workspace-split-design.md）

---

## 开发与部署

- 本地：`cd backend && make test && make run-dev`（单测/本地后端）、`cd frontend && npm run dev`（前端热更）
- 构建 → kind 导入 → 部署 → 更新镜像 rollout restart：全流程见 [docs/deployment.md](docs/deployment.md) §一~§三 + 运维备忘

---

## CI 与提交流程

CI 配置真相源在 [.github/workflows/](.github/workflows/)（backend-ci / frontend-ci / agent-framework-ci / charts-ci / platform-e2e-ci）；镜像构建与 tag 派生见各模块 Makefile。核心不变量：

- **push 仅 master** 出镜像；其余分支只在 PR 时触发，PR run 是唯一门禁口径
- **job 按改动目录过滤**，不相关目录 Skipped 视为通过（刻意设计：PR 必须照常触发工作流，否则必需检查 pending 卡死），禁止在工作流层面用 `paths` 过滤
- **concurrency 组含 workflow 维度**，勿回退共享组名（曾致 master push 镜像推送被静默取消）
- **分支保护**：strict（合并前基于最新 master）+ enforce_admins（对 master 直接 push 被拒）+ 禁 force push/删分支，一切变更走 PR
- **九项必需检查**：三个单测（mvn test / go vet+go test / lint+build）+ agent-framework 四 E2E（核心/多副本/沙箱/协议多副本 P 组）+ `E2E 平台（kind + 控制台全功能）` + `Chart 校验 (helm lint + template)`
- 新增 job 默认非必需，转必需由管理员在分支保护加勾（job name 须与保护规则字符串完全一致）；`E2E 沙箱生命周期（L 组）` 转必需前须满足观察期（sandbox-pool-opensandbox-design §9.5）

日常提交流程（worktree 工作流）：

```bash
git checkout master && git pull                                    # 0. 主工作区保持 master
git worktree add .tmp/<名称> -b docs/xxx master && cd .tmp/<名称>  # 1. 开发分支（feat/|fix/|chore/|docs/），推分支即触发 CI 验证
# 2. 开 PR 到 master（gh pr create 或网页）→ 必需检查全绿后合并
git worktree remove .tmp/<名称> && git branch -d docs/xxx          # 3. 合并后清理
```

- 临时提交不慎落在本地 master：切新分支提 PR（`git checkout -b chore/xxx && git push`），不要绕过门禁
- AI 生成的提交：conventional commit + 尾行 `Chat-Session: <对话id>` trailer
- 业务镜像更新到 kind 集群是手动流程（`docker save | ctr import` → rollout），CI 只负责测试与镜像分发

---

## 文档规范

- 文档命名日期规则见「行为规范-开发中」；项目口径：存量文档不重命名，AGENTS.md / README.md 等工具入口名豁免
- 文档只留索引与唯一信息：不复述代码/设计文档已有的逻辑，一律指向源，防止文档与代码形成两套信息源
- 设计文档归档与状态约定见 [docs/design/README.md](docs/design/README.md)（新增设计须在其清单补行）；部署指南 docs/deployment.md、OAF 规范 docs/oaf-specification.md、实施记录 docs/implementation/

---

## 子模块 AGENTS.md 索引

- [backend/AGENTS.md](backend/AGENTS.md) — Go 后端：目录结构、REST/MCP 契约、状态机、K8s 对象构造、安全限制
- [agent-framework/AGENTS.md](agent-framework/AGENTS.md) — Java 运行时：A2A/单次流端点、MCP 注册（fail-soft/read_only/HITL）、环境变量、Debug Console

# 控制面拆分设计：运维侧 + 用户侧（对标 AgentCore，引入用户与工作空间）

> 状态：**设计稿（待评审）**，2026-10-16。配套前端设计 Demo 见本文档 §14（可交互 HTML 原型）。
> 本文为评审用设计稿；实施前按评审结论修订，实施完成后按仓库约定回写状态与本表（docs/design/README.md）。

---

## 1. 背景与目标

### 1.1 背景

当前平台是**单一运维视角**的控制面：上传 OAF 包 → 发布为 K8s 服务 → 查看/运维。前端无登录、无用户概念（`userId` 硬编码 `"webui"`，frontend/src/app/assistant/page.tsx:469）；backend 无用户/租户实体（services/oaf_packages/platform_config 五张表均无归属维度，backend/internal/store/model.go）。

业务侧 agent-framework 已有**事实上的用户原语**：`X-User-Id` 头 + `session_user` 表 + `agents/{agent}/users/{uid}` KV 命名空间隔离（KV/技能/沙箱 guard 均按 userId 分域），但身份是客户端自报字符串、无鉴权（agent-framework config/UserIdHeaderFilter.java:38-90，注释"网关层负责鉴权"——而网关层目前并不存在）。

参考对象：阿里云「智能体构建和治理平台 AgentCore」（help.aliyun.com/zh/agentcore/agentcore-product-overview + 控制台实际页面存档）。其产品定位为：以**构建与运行、协作与治理、观测与评估**三大能力覆盖 Agent 全生命周期；通过**工作空间**隔离资源、**多模式构建入口**（托管 Harness / 自定义镜像 / 纳管）、统一身份鉴权与 Team 协作编排，将自研、开源 Harness、商业 SaaS Agent 多来源统管。

### 1.2 目标

1. **控制台一分为二**：
   - **运维侧**（ops）：承载现有全部功能（服务/包/发布/平台默认配置），面向平台管理员，另新增工作空间管理、用户管理等平台治理页面；
   - **用户侧**（console，对标 AgentCore）：面向 Agent 开发者/业务用户，以**用户**登录、以**工作空间**隔离资源，提供 Agent 构建（创建/纳管/调试/会话/集成其它 Agent）、资产管理（模型连接/Skill/MCP 工具/记忆/凭证）；观测大盘一期不做（指标与 Trace 后续从可观测系统接入）。
2. **引入用户与工作空间**：backend 新增 users / workspaces / workspace_members / api_keys 等实体与登录鉴权（JWT）；存量 services/oaf_packages 挂接 `workspace_id` 归属。
3. **业务面零改动**：agent-framework 不做任何修改，用户身份经既有 `X-User-Id` 机制透传；数据面（对话流量）依旧不经过 backend。
4. **存量零破坏**：现有 REST/MCP 路径、AUTH_TOKEN 语义、e2e 门禁全部保持不变。

### 1.3 非目标（本期不做）

- 不做组织级多租户计费/计量（AgentCore 的 CU 计费模型仅作远期参考）；
- 不做知识库实体（平台当前无此能力，用户侧仅占位入口，见 §8.6）；
- 不做 Team 多 Agent 协作编排 UI（多 Agent 编排已有 travel-fulfillment B1 形态底座，控制台化列远期）;
- 不改 agent-framework 任何代码、不改业务 K8s 对象写路径语义。

---

## 2. 现状事实（设计输入）

以下事实来自本次代码调研，行号以当前工作区为准：

**backend（Go 控制面）**
- 路由全部挂 `/api/v1`（backend/internal/handler/router.go:20），统一包络 `{code,message,data}`（respond.go:15-26）；仅有一个**全局可选** `AUTH_TOKEN` Bearer 鉴权（middleware.go:24-37），无用户/会话/JWT/API-key 概念；CORS 全开放。
- 数据模型五张表：`oaf_packages` / `services` / `service_events` / `platform_config` / `platform_config_events`（store/model.go），**均无 user/workspace 维度**；迁移方式为 GORM AutoMigrate（store/db.go:17-18），无 SQL 文件。
- `ServiceEntity` 关键字段：id / k8s_name(uniqueIndex) / display_name / package_id / image / env_json / env_secret_json / replicas / status / endpoint / short_name / agent_card_json / …（model.go:38-66）。
- MCP `/mcp` 与 REST 同进程，注册 17 个 tool（mcpsrv/server.go），与运维操作一一对应。
- backend ↔ agent-framework 运行时耦合仅一处：A2A 注册拉卡，集群内 Service 直连 `http://{k8sName}-svc...:8100`（service/publish.go:170, register.go:61-89）。
- 红线：启动路径禁止 reconcile/批量迁移业务 K8s 资源；backend 单副本语义。

**agent-framework（Java 业务面）**
- 用户隔离机制齐备：`X-User-Id` → session_user 表、`/threads?userId=` 过滤、KV `agents/{agent}/users/{uid}` 命名空间、用户级技能 L4（UserSkillController，缺 header 报 400）、沙箱 guard key `sbx:guard:user:{uid}`。
- 会话面 HTTP 端点：`/threads/chat`（SSE）、`/threads/{sid}/confirm-stream`、`/threads/{sid}/subscribe`（durable SSE 续传）、`/threads`、`/threads/{sid}/history`、`/models`、`/skills*`、`/files/*` 等（controller/ 目录）。

**frontend（Next.js 16）**
- 单一顶栏布局（app/layout.tsx:11-17），8 个页面：服务列表 / 发布向导 / 服务详情 / 包列表 / 包详情 / 包编辑 / 发布助手 / 平台配置；无登录、无路由守卫、无组件库、无状态库；SSE 用 fetch 流手解。
- `src/proxy.ts` 同源反代：`/api/v1/* → $BACKEND_INTERNAL_URL`、`/agent/release-agent/* → $AGENT_INTERNAL_URL`（运行时读 env，构建期不烘焙）。
- 发布助手已按 durable 会话打通对话全链路（HITL 确认卡 / 附件 / 会话历史），是用户侧「调试对话」页的现成范本。

---

## 3. AgentCore 功能盘点与映射

### 3.1 AgentCore 控制台信息架构（取自控制台页面存档）

顶栏：地域 / 费用 / 文档 / 语言 / 账号；**工作空间切换器**（如 `test`，workspace id 形如 `ws-xxxx`）。
左侧导航：

```
概览
观测大盘
AGENT 构建
  ├ Agent            （列表：总数/正常/异常统计卡 + 筛选 + 「纳管 Agent」「创建 Agent」）
  ├ Agent 模板
  └ 连接器
资产管理
  ├ 模型连接
  ├ Skill 管理
  ├ 工具管理
  ├ 知识库
  ├ 记忆空间
  └ 凭证管理
AGENT 协作
  └ Team 协作
身份与权限
  ├ 用户管理
  └ 账号集成
```

创建 Agent 向导五步（存档原文）：
1. **基本信息**：创建方式（托管 Harness / 自定义代码·镜像 / 快速创建[即将上线]）、Harness（如 Qwen Paw 通用助手 / Qoder CLI 编码助手）、Agent 模板、名称、模型连接、模型、Token 配额、描述；
2. **文件配置**：系统提示词（SOUL.md / AGENTS.md，不选模板时仅需提示词）；
3. **Agent 资产配置**：Skill / SubAgent / 工具 / 知识库（经 MCP 接入、穿梭框绑定）/ 记忆 / 凭证；
4. **资源配置**：规格（1核2G / 2核4G / 4核8G）、实例最大 Session 数（1-20）、Session TTL（60-86400s）、最小实例数（0-100）、最大实例数（0-100）；
5. **网络配置**：默认网卡访问公网开关、访问 VPC。

### 3.2 映射表（AgentCore 概念 → 本平台承载）

| AgentCore 概念 | 本平台对应承载 | 现状能力 | 一期动作 |
|---|---|---|---|
| 工作空间 workspace | `workspaces` 表 + 前端切换器 | 无 | 新建实体；存量服务/包回填默认工作空间 |
| 用户/身份与权限 | `users` + `workspace_members` + JWT | 仅 AUTH_TOKEN 全局 token | 新建；角色 admin/user，admin 双侧可进 |
| Agent（托管 Harness） | `services`（创建页生成 OAF 包 + agent-framework 镜像发布） | 发布链路完整 | 用户侧创建页 = **页面填写配置 → 平台生成 OAF 包（登记为 v1）→ 复用既有发布链路** |
| Agent（自定义代码/镜像） | `services`（自定义 image + OAF 包约定） | 已支持任意镜像 | 创建页「导入 OAF 包」方式（自定义镜像一期不做，列远期） |
| 纳管 Agent | 存量服务挂 workspace 归属 | 无归属字段 | 运维/管理员把无主服务纳管进工作空间 |
| Agent 模板 | 精选 OAF 包（打 `template` 标记） | 包体系已有 | **不作为创建入口**；模板页保留为公共包浏览，复用其资产走「导入 OAF 包」方式派生（copy-on-write） |
| 模型连接 | 工作空间级模型连接配置（backend 自有表）→ 下发至 Agent 的 `/models` 接口（model_config，见 agent-framework/docs/session-model-switch-design.md） | 框架侧 CRUD+test+会话级切换已实施；连接配置/下发/使用展示为新建 | 用户侧模型连接页 = 连接 CRUD + 连通性测试 + **下发**（配置同步到 Agent 的 `POST/PATCH /models`，幂等）+ **Agent 使用情况展示**（每 Agent 已同步连接/默认模型/同步状态）；调试对话模型切换走 `POST /threads/chat {model}`（数据源 = 该 Agent 自己的 `/models`） |
| Skill 管理 | **平台 Skill 中心（工作空间技能资产，独立于 OAF 包）**：`skills` / `skill_versions` / `skill_releases` / `skill_market_items` 四表（§6.3）——创建入口两种（zip 技能包上传 / Markdown 定义，对标 AgentCore create-skill）+ Skill 广场市场导入（skill-market 精选目录），版本管理（工作区草稿可续改 + 正式版本不可变快照 + 最新发布版承担 latest 语义，对标 AgentCore skill-version-management），列表管理（详情/启用停用/删除/下载） | 包内 skills/ 目录有完整链路；无独立技能资产实体 | 用户侧 Skill 页 = **我的 Skill（空间技能资产 CRUD + 版本管理）+ Skill 广场（多源市场预留，一期内置源一键导入）+ Agent 使用情况（按最后生成 OAF 包解析的引用总览与单 Agent 详情）**；创建/编辑 Agent 时从本空间技能资产勾选（写入生成包 frontmatter `skills` 声明 + 复制 `skills/<name>/` 目录，随包自包含）；**按 Agent 查询引用**（Agent→技能 与 技能→Agent 双向视图）。**不做运行时分级（L1-L4）管理**——分级是 Agent 运行时概念（2026-10-17 口径修订）；包内 skills/ 目录仍按 2026-10-17 口径只读展示（由生成包/包编辑器维护） |
| 工具管理（MCP） | 工作空间级 MCP 连接器配置（backend 自有表）→ Agent 创建/更新时勾选，发布时注入生成包（`mcpServers`/`mcp-configs`） | 包声明已有；连接器配置为新建 | 用户侧工具页 = MCP 配置创建/维护（对标 AgentCore tools/create）；已发布 Agent 的运行时枚举（`/tools` `/mcp`）与三态权限只读展示 |
| 集成其它 Agent（远程子 agent） | Agent Protocol 远程 SubAgent（`SubagentDeclaration.url` → 对端 `POST /tasks`，见 travel-fulfillment-agent-protocol-design.md） | M1 已实施（双服务实测 + e2e 协议多副本门禁） | 用户侧「集成 Agent」页：登记可集成的 Agent 端点与 lead→member 集成关系；创建页资产配置区勾选 → 写入生成包 `subAgents[].endpoint` |
| 知识库 | —— | 无 | 仅占位入口，标注「规划中」（经 MCP 接入同 AgentCore 思路） |
| 记忆空间 | **不新建实体——每个运行中 Agent 即一个记忆空间**：长期记忆 = agent-framework `agent_fs` KV（MEMORY.md + memory/ 按日文件，SDK flush/consolidation 自动沉淀，`agents/{agent}/users/{uid}` 隔离）；会话历史 = session_user + session_message 归档（2026-10-19 重设计，对标 AgentCore 列表页 + 详情页三 Tab，§8.4） | 记忆文件/会话查询调试面已有（`/debug/memory`、`/threads`、`/threads/{sid}/history`） | 一期**纯只读展示**：记忆空间页 = 空间列表（空间内 Agent 卡片）+ 详情页三 Tab（概览 / 长期记忆 / 会话历史），前端直连业务 Pod，不经 backend；写管理/语义检索/独立空间实体远期（§8.4） |
| 凭证管理 | API Key（调用 agent 的凭证）+ 敏感 env Secret 化 | Secret 路由已有、API Key 无 | 新建 `api_keys` 实体与签发/吊销页 |
| 调试对话（Playground） | `/threads/chat` SSE + HITL | 发布助手页已打通全链 | 复用为 Agent 详情「调试」Tab（按 agent 直连） |
| 会话管理 | `/threads` + `/subscribe` + history | 已有 | Agent 详情「会话」Tab（userId=当前用户） |
| 观测大盘 | 可观测系统（指标/Trace）+ eval-studio（18400） | 飞轮已有独立服务 | **一期不做**：指标与 Trace 后续统一从可观测系统接入；当前仅保留页面占位 + eval-studio 外链 |
| 资源配置（规格/Session/实例数） | replicas + DEPLOYMENT_TEMPLATE 资源档 | replicas 已有 | 规格三档（1c2g/2c4g/4c8g）后端内置映射；min=replicas，max/Session TTL 远期（HPA 不在本期） |
| 网络配置（公网/VPC） | Ingress 发布（router/host 双模式） | 已有 | 创建页「网络配置」区=是否发布对外访问入口 |
| Token 配额 | —— | 无 | **不做**：不提供占位开关；计量依赖后续可观测体系，届时一并设计 |
| Team 协作 | 多 Agent 编排（Agent Protocol B1 形态） | 底座已有、无 UI | 远期 |

> 设计取向：**不新建一套 Agent 实体**。用户侧「Agent」就是现有 `services` 行挂上 workspace 归属——避免双实体同步；AgentCore 语义里 Agent≈可运行智能体实例，与我们的 service 一一对应。

---

## 4. 总体架构

```
                         ┌────────────────────────────────────────────┐
   浏览器                 │              frontend (Next.js)             │
  ┌──────────┐           │  /login         登录（JWT → httpOnly cookie）│
  │ 运维管理员 │──────────▶│  /admin/*      运维侧（现有页迁移 + 新治理页）│
  │ 开发者用户 │──────────▶│  /console/*    用户侧（对标 AgentCore IA）   │
  └──────────┘           │  proxy.ts: /api/* → backend；/agent/* → 业务 │
                         └────────────────────────────────────────────┘
                              │                        │
                    控制面（元数据/鉴权）          数据面（对话/文件，不经 backend）
                              ▼                        ▼
                ┌──────────────────────────┐   ┌─────────────────────────────┐
                │ backend :8080 (Go)       │   │ platform-router / Ingress    │
                │  /api/v1/auth/*    新增   │   │   → oaf-{short}-svc :8100    │
                │  /api/v1/console/* 新增   │   │   agent-framework（零改动）   │
                │  /api/v1/admin/*   新增   │   │   X-User-Id 透传平台身份      │
                │  /api/v1/{services,     │   └─────────────────────────────┘
                │   packages,...} 存量保留  │            │
                │  /mcp             保留    │   ┌─────────────────────────────┐
                └──────────────────────────┘   │ MySQL oaf_platform           │
                        AutoMigrate ▼          │  + users/workspaces/         │
                    （新表 + 存量回填，不碰 K8s） │    workspace_members/api_keys│
                                               │  + services.workspace_id 等  │
                                               └─────────────────────────────┘
```

三条不变量（对照 AGENTS.md 架构强约束）：
1. **原则一**：新增的用户/工作空间治理只写 backend 自有表与自有 K8s 对象（经既有显式发布 API），启动路径零 reconcile；用户侧对话流量仍走 router → 业务 Pod 直连，不经过 backend。
2. **原则二**：业务面零改动；用户隔离完全复用 agent-framework 既有 `X-User-Id` 机制；影响正确性的新状态（用户/工作空间/API Key）全部落共享 MySQL。
3. **存量兼容**：现有 `/api/v1/*` 路径与 AUTH_TOKEN 语义原样保留（视为运维侧 API），e2e / release-agent MCP 不感知本次拆分。

---

## 5. 身份与鉴权设计

### 5.1 实体与角色

- **User**：`role ∈ {admin, user}`。admin 可进运维侧+任意工作空间（以「平台管理员」工作空间成员身份）；user 仅可进自己所属工作空间。
- **Workspace**：`slug` 全局唯一（展示为 `ws-{8位随机}`，同 AgentCore 风格，仅展示形态）、名称、描述、`status ∈ {active, disabled}`、`quota_json`（一期仅记录 `max_agents`，不强制）、owner。
- **WorkspaceMember**：`(workspace_id, user_id, role ∈ {owner, admin, member})`。一期权限从简：owner/admin 可发布/改资产，member 只读+调试。

### 5.2 认证流程

- `POST /api/v1/auth/login`（username+password）→ 校验 bcrypt → 签发 JWT（HS256，claims: sub/userId/role/exp，默认 12h）→ 前端写 httpOnly cookie（`oaf_session`），响应体同时返回 token 供非浏览器场景。
- `GET /api/v1/auth/me` → 当前用户 + 所属工作空间列表 + 默认工作空间。
- `POST /api/v1/auth/logout` → 前端清 cookie（JWT 无状态，不做服务端吊销；改密/禁用靠 exp+短期生效，一期可接受，文档记录取舍）。
- 密码：bcrypt；首个 admin 用户由**环境变量种子**创建（`ADMIN_INIT_USER`/`ADMIN_INIT_PASSWORD`，仅当 users 表为空时生效，幂等）——避免启动写业务数据的红线争议（写的是平台自有表，且一次性）。

### 5.3 鉴权中间件分层

| 端 | 鉴权方式 | 说明 |
|---|---|---|
| `/api/v1/auth/*`、`/healthz` | 无 | 登录与健康检查 |
| `/api/v1/console/*` | JWT（任意角色） | 工作空间内权限由 workspace_members 裁决 |
| `/api/v1/admin/*` | JWT 且 role=admin，**或** AUTH_TOKEN | 新治理页；AUTH_TOKEN 兼容脚本 |
| `/api/v1/{services,packages,images,platform-config}`（存量） | 维持现状（AUTH_TOKEN 可选） | 一期不动，二期收敛为 admin 前缀 |
| `/mcp` | 维持现状（AUTH_TOKEN） | 运维助手继续可用 |
| 数据面 `/agent/{short}/*` | 一期：前端 proxy 注入 `X-User-Id`；二期：router auth_request（见 §5.4） | 业务 Pod 不感知鉴权 |

### 5.4 数据面身份与网关鉴权（分期）

- **一期（控制台内）**：用户侧调试/会话请求由 frontend `proxy.ts` 代理 `/agent/{short}/*` 时校验 cookie JWT 并注入 `X-User-Id: u-{userId}`（替代现在硬编码的 `"webui"`）。绕过 frontend 的直连调用仍可行（与今天同等暴露面），但控制台路径上身份可信。
- **二期（API Key 网关）**：platform-router nginx 增加 `auth_request /_auth` 子请求 → backend `GET /api/v1/auth/introspect`（校验 JWT 或 `Authorization: Bearer ak-...` API Key，返回注入 `X-User-Id`）。auth 子请求是短平快元数据调用，业务流量本体不经 backend，不违反原则一；`introspect` 失败策略 fail-closed（可配）。此步落地后 `api_keys` 才真正对对外调用生效。

---

## 6. 数据模型与迁移

backend 维持 GORM AutoMigrate 风格（与现状一致，backend 无 Flyway；Flyway 纪律仅约束 agent-framework），新增 `internal/store/migrate.go` 做幂等数据回填（仅平台自有表，**不碰业务 K8s 资源**）。

### 6.1 新增表

```go
// users
ID uint; Username string uniqueIndex; PasswordHash string; DisplayName string;
Email string; Role string; Status string; LastLoginAt *time.Time; CreatedAt/UpdatedAt

// workspaces
ID uint; Slug string uniqueIndex; Name string; Description string;
OwnerUserID uint index; Status string; QuotaJSON string; CreatedAt/UpdatedAt

// workspace_members
ID uint; WorkspaceID uint index; UserID uint index; Role string;
UniqueIndex(workspace_id, user_id)

// api_keys
ID uint; WorkspaceID uint index; UserID uint; Name string;
KeyPrefix string（ak-前8位，用于列表识别）; KeyHash string（sha256，不存明文）;
Status string; LastUsedAt *time.Time; ExpiresAt *time.Time; CreatedAt
```

### 6.2 存量表扩展

```go
// services 增列
WorkspaceID uint index（0 = 未归属/仅运维可见）; CreatedBy uint
// oaf_packages 增列
WorkspaceID uint index（0 = 平台公共包，双侧可见）
```

### 6.3 Skill 中心四表（2026-10-19 新增，对标 AgentCore Skill 管理 + 版本管理）

技能从「OAF 包附属」升级为**工作空间级独立资产**：用户可经市场/zip 上传/Markdown 定义创建，
创建/编辑 Agent 时勾选注入生成包；支持版本管理（工作区草稿可续改 + 正式版本不可变快照 +
最新发布版承担 latest 语义）与引用追溯（按 Agent / 按技能双向查询）。四张表全为平台自有表（原则一：不碰业务 K8s 资源；AutoMigrate 同步建表）。

```go
// skills 技能资产主表（name 空间内唯一；语义对齐 AgentCore Skill）
ID uint; WorkspaceID uint index; Name string; // 技能名（skillNameRe 口径：^[a-z0-9]([a-z0-9-]*[a-z0-9])?$，≤64 字符）
DisplayName string; Description string（≤1024，SKILL.md frontmatter 同源）;
TagsJSON string; // ["客服","工单"] 展示用标签
Source string; // market | zip | markdown  创建方式
SourceName string; // market 来源市场名（如「平台精选」；多源预留，内置源写入「平台精选」）
LatestVersionID uint; // 指向 skill_versions 最新版本行（发布后=「最新发布版」展示目标）
Enabled bool; // 启用/停用（对标 AgentCore 状态开关：停用后引用它的 Agent 不再注入该技能）
Status string; // active | disabled
CreatedBy uint; CreatedAt/UpdatedAt
// UniqueIndex(workspace_id, name)

// skill_versions 版本行（对标 AgentCore：工作区草稿 HEAD 可续改 + 正式版本不可变快照）
ID uint; SkillID uint index; Version string; // 语义化版本 major.minor.patch（发布路径生成）
Status string; // draft（工作区，可续改）| review（审核中，预留）| pending（待发布）| published | offline
Changelog string; // 变更说明
ContentJSON string; // Markdown 定义形态的正文；zip 形态为空
FilesJSON string; // zip 形态的文件清单 [{path,size,sha256}]（内容本体落 PVC，见下）
StorageDir string; // PVC 相对路径 skills/{id}/{version}（zip 解包内容，与 oaf_packages 同盘复用机制）
Checksum string; // zip sha256
CreatedBy uint; CreatedAt/UpdatedAt
// UniqueIndex(skill_id, version)

// skill_releases 发布记录（版本生命周期审计：draft→published→offline 可重新上线）
ID uint; SkillID uint; VersionID uint; Action string; // publish | offline | reline
Note string; CreatedBy uint; CreatedAt

// skill_market_items Skill 广场条目（只读表；安装=按条目建 skill 资产）；多源预留见 skill_market_sources
ID uint; SourceID uint index; // 所属市场源（一期恒为内置源 1）
Key string; // 市场键，源内唯一（如 ticket-escalation）
Name; DisplayName; Description; TagsJSON; Downloads int;
BundleDir string; // 内置 zip 的只读路径（镜像/PVC）；外部源为远端 zip URL（远期）
Enabled bool
// UniqueIndex(source_id, key)

// skill_market_sources 市场源注册表（2026-10-19 多源预留；一期仅一行内置源，不开放 UI 维护）
ID uint; Key string; // builtin | 外部源自定义键
Name string; // 展示名（如「平台精选」「团队市场」）
Type string; // builtin | registry  registry=远端目录源（预留，远期开放）
Endpoint string; // registry 源的目录 JSON 地址（返回条目数组 + zip 下载 URL）
Enabled bool; Sort int
```

**多源市场预留（2026-10-19）**：广场按「市场源」聚合，导入/安装语义与源解耦——`GET
skills/market?source=` 按源过滤条目，`POST skills/market/:source/:key/install` 按源安装
（内置源=复制镜像内 zip；registry 源=远期经 HTTP 拉取 zip，落盘与合规检查同管线）。一期仅
实现内置源（`skill_market_sources` 种子一行 `builtin`），registry 源只建模型与 API 形参、
不开放：外部目录协议（认证方式/条目 schema/zip 校验）待接入真实市场时单独评审。

存储复用：zip 技能包落共享 PVC（`skills/{skillID}/{version}/`，与 `packages/{id}` 同盘、
同 `MaxZipSize 20MB / MaxEntries 2000 / MaxExtracted 100MB` 护栏）；zip 布局支持三种并**归一化为** `skills/<name>/SKILL.md` 存储——A) `skills/<name>/SKILL.md`
（OAF 包内技能目录风格）；B) 根目录 `SKILL.md`（标准技能包，如 Claude/AgentCore 技能包，
可带 `_meta.json`）；C) 单层目录 `<dir>/SKILL.md`（压缩整个技能文件夹）。技能名优先级：
表单 name > 布局目录名 > SKILL.md frontmatter name > zip 文件名净化（2026-10-20 部署实测修订：
原实现只认 A，用户上传标准技能包报 `zip must contain skills/<name>/SKILL.md`）；上传即跑
`checkSkillDirs` 合规检查
（skillcheck.go 同一实现，warnings 宽松不阻断、明细回显）。

与 OAF 包的关系（沿用 copy-on-write 自包含语义，**运行时零改动**）：
- 创建/编辑 Agent 勾选技能资产 → 生成包 frontmatter `skills` 声明 + 把该技能**当前发布版**
  的目录复制进包内 `skills/<name>/`（agentgen.go copySkillDir 既有机制，来源从「包」换
  「技能版本 PVC 目录」）——包仍自包含，业务 Pod 直挂 PVC 运行，不经 backend；
- **版本快照即生成包本身**：勾选的技能版本复制进包内 `skills/<name>/`，frontmatter `skills`
  声明记录 `{name, source, version}`——**最后生成的 OAF 包就是引用快照的事实来源**，不新增
  services 列（`services.skills_json` 已被 A2A agent-card 注册占用，register.go 写卡片 skills，
  语义不同不混用）。升级技能版本必须经编辑页重新勾选/重新生成包，已发布 Agent 的包内容不随
  技能中心变化（对标 AgentCore「按具体版本号引用始终不变」）；「Agent 使用情况」视图按
  services.package_id 解析当前包聚合（与沙箱总览同构，无新表、无后台任务）；
- 运行时技能分级（L1-L4）仍由 agent-framework 维护，零改动。

### 6.4 回填策略（migrate.go，幂等）

1. users 空表 → 种子 admin（§5.2）；
2. 不存在 `slug=default` 工作空间 → 创建「默认工作空间」，并把 owner 指向种子 admin；
3. `services.workspace_id = 0` 的行 → 置为默认工作空间（用户侧「纳管」的存量形态：默认空间对普通用户不可见，由管理员决定是否分配给业务空间）；
4. `oaf_packages` 同理 → 公共包语义改为 `workspace_id = 0` 保留（不强制回填，用户侧「模板/公共包」入口读取 0 值包）。

启动顺序：AutoMigrate → backfill → 路由装配；全部幂等，二次启动零写入。

---

## 7. API 设计（新增面）

统一包络与错误映射沿用 respond.go。以下路径均为新增，不改存量。

### 7.1 认证

```
POST /api/v1/auth/login            {username, password} → {token, user}
GET  /api/v1/auth/me               → {user, workspaces[]}
POST /api/v1/auth/logout
GET  /api/v1/auth/introspect       （二期，供 router auth_request）
```

### 7.2 用户侧 console（JWT；workspace 以 `X-Workspace-Id` 头或路径段指定，默认取 `me` 第一个）

```
GET  /api/v1/console/workspaces/:ws/summary        概览统计（agent 数/状态分布/最近发布/最近会话）
GET  /api/v1/console/workspaces/:ws/agents         Agent 列表（= services 按 workspace 过滤，附 agent_card 摘要）
POST /api/v1/console/workspaces/:ws/agents         创建并发布（body 为创建页表单；服务端生成 OAF 包 v1 后复用 Core.Publish，见 §8.2）
GET  /api/v1/console/workspaces/:ws/agents/:id     详情
POST /api/v1/console/workspaces/:ws/agents/:id/{unpublish,publish,republish}
DELETE /api/v1/console/workspaces/:ws/agents/:id   （仅 owner/admin）
GET  /api/v1/console/workspaces/:ws/templates      模板包列表（oaf_packages 公共包 + template 标记）
GET/POST/PATCH/DELETE /api/v1/console/workspaces/:ws/model-connections   模型连接 CRUD（工作空间级配置；name/modelId/baseUrl/apiKey/参数，对标 AgentCore 模型管理）
POST /api/v1/console/workspaces/:ws/model-connections/:id/test          连通性测试（backend 直连 baseUrl 最小 completion）
POST /api/v1/console/workspaces/:ws/model-connections/:id/deliver       下发：把连接配置同步到指定 Agent 的 POST/PATCH /models（幂等）
GET  /api/v1/console/workspaces/:ws/model-connections/usage             Agent 使用情况（每 Agent 已同步连接/默认模型/同步状态）
GET  /api/v1/console/workspaces/:ws/skills         空间技能资产列表（§6.3 四表聚合；含引用计数）
POST /api/v1/console/workspaces/:ws/skills         创建技能（multipart: zip 技能包 | form: Markdown 定义 + name/description/tags/可见性）
GET  /api/v1/console/workspaces/:ws/skills/:id     详情（基本信息 + 版本列表 + 引用 Agent 列表 + 标签）
PATCH /api/v1/console/workspaces/:ws/skills/:id    编辑（工作区版本内容/描述/标签/启用停用）
DELETE /api/v1/console/workspaces/:ws/skills/:id   删除（无 Agent 引用时允许；有引用 409）
GET  /api/v1/console/workspaces/:ws/skills/:id/versions       版本列表（状态/变更说明/时间）
POST /api/v1/console/workspaces/:ws/skills/:id/versions       创建新版本（基于指定版本 + 变更说明）
POST /api/v1/console/workspaces/:ws/skills/:id/versions/:vid/{publish,offline}  发布/下线版本（published↔offline）
GET  /api/v1/console/workspaces/:ws/skills/:id/versions/:vid/download          下载版本 zip
GET  /api/v1/console/workspaces/:ws/skills/market?source=   Skill 广场目录（skill_market_items 按 source 过滤 + 已安装标记 + 下载量排序；sources 一并返回，多源预留见 §6.3）
GET  /api/v1/console/workspaces/:ws/skills/market/sources   市场源列表（一期仅内置源 builtin；registry 源远期开放）
POST /api/v1/console/workspaces/:ws/skills/market/:source/:key/install         安装市场技能（内置源=复制 zip → 本空间 skill 资产 v1.0.0 published；registry 源远期经 HTTP 拉取）
GET  /api/v1/console/workspaces/:ws/skill-usages   **Agent 使用情况**（第三 Tab 数据源）：遍历空间内 services，按各自**最后生成的 OAF 包**（services.package_id 当前指向版本）解析——包内 skills/ 目录扫描（事实来源）+ frontmatter `skills` 声明（意图与版本），聚合 `AgentUsageItem[]`（agentId/agentName/status/packageId/packageVersion/skills[{name,declared,version,matchedSkillId,matchedVersion}]/skillCount）；与沙箱总览（consoleview.go ConsoleSandbox）同构：无新表、无后台任务，请求期实时解析包文件
GET  /api/v1/console/workspaces/:ws/skill-usages/:agentId      单 Agent 详情：该 Agent 当前包的技能完整清单（声明+目录扫描逐条，含 SKILL.md 描述与合规 issues）+ 包版本链信息（版本号/生成时间）——列表行「查看详情」抽屉数据源
GET  /api/v1/console/workspaces/:ws/skills/:id/usages          按技能查询引用 Agent（技能 → Agent 列表 + 各自锁定版本；反向 = 遍历同上聚合，按 matchedSkillId 过滤）
GET  /api/v1/console/workspaces/:ws/skill-refs     创建/编辑页候选数据源（空间技能资产 + 公共只读技能，Enabled 仅 active）
GET/POST/PATCH/DELETE /api/v1/console/workspaces/:ws/tools    MCP 连接器配置 CRUD（保存为空间级配置，创建/更新 Agent 时注入生成包）
GET/POST/DELETE /api/v1/console/workspaces/:ws/integrations   集成 Agent：可集成端点注册与 lead→member 集成关系（Agent Protocol）
（不新增列表端点）记忆空间列表复用 `GET /console/workspaces/:ws/agents`（Agent 即记忆空间，§8.4）；卡片上的记忆文件数/会话数为前端直连各 Agent 数据面（`/debug/memory`、`/threads`）聚合
GET  /api/v1/console/workspaces/:ws/api-keys       签发/列表/吊销
POST /api/v1/console/workspaces/:ws/api-keys
DELETE /api/v1/console/workspaces/:ws/api-keys/:id
GET  /api/v1/console/workspaces/:ws/members        成员管理（owner/admin）
POST /api/v1/console/workspaces/:ws/members
```

> 会话/对话（`/threads/chat`、`/subscribe`、`/history`…）**不**经 backend：前端直连 `/agent/{short}/*`（proxy 注入身份），与发布助手页现状同构——数据面不进控制面。

### 7.3 运维侧 admin（JWT-admin 或 AUTH_TOKEN）

```
GET/POST /api/v1/admin/workspaces          列表/创建（slug 生成、owner 指派）
GET/PATCH /api/v1/admin/workspaces/:id     详情/停用/改配额
DELETE /api/v1/admin/workspaces/:id        仅空空间可删（无成员无 agent）
GET  /api/v1/admin/users                   用户列表
POST /api/v1/admin/users                   创建（重置密码走 PATCH）
PATCH /api/v1/admin/users/:id              角色/状态/重置密码
GET  /api/v1/admin/workspaces/:id/agents   空间内服务（跨空间治理视图）
POST /api/v1/admin/workspaces/:id/bind     纳管：把存量 service 挂入空间
GET  /api/v1/admin/audit-logs              审计（一期仅 service_events + platform_config_events 聚合视图；独立 audit_logs 表列二期）
```

### 7.4 MCP（远期）

一期 MCP 保持运维 17 tool 不变；用户侧 MCP（`create_agent`/`debug_agent` 等）列二期，届时按 workspace 上下文注册独立 tool 组。

---

## 8. 用户侧功能明细（对标 AgentCore，一期范围）

### 8.1 概览

工作空间维度统计卡（Agent 总数 / 运行中 / 异常）+ 最近发布时间线 + 快捷入口（创建 Agent / 打开调试 / 打开 eval-studio）。数据来自 `console/workspaces/:ws/summary`。

### 8.2 Agent 列表与创建页

列表：统计卡 + 卡片网格（对标 AgentCore `pageSize=12` 卡片流）+ 状态筛选；卡片含名称/状态徽标/模型/最近更新；操作：调试、详情、下线/上线。顶部按钮「创建 Agent」「纳管 Agent」（纳管仅在有可纳管存量服务时出现）。

创建页 = **单页分区表单**（对标 AgentCore 创建页：右上角「立即创建」+ 表单底部同按钮，无上一步/下一步分步切换；主创建方式与 AgentCore 一致——**页面填写配置 → 生成 OAF 包 → 发布**，而非从模板派生）。自上而下：

- **选择创建方式**（顶部卡片三选，带说明）：**托管 Harness**（默认，即 agent-framework 托管镜像）/ **导入 OAF 包**（上传 zip 或选公共包派生，copy-on-write）/ **纳管已有服务**（管理员场景）；
- **1 基本信息**：Agent 名称（必填，K8s short 名与 frontmatter `agentKey`/`slug` 的同源派生源）；运行镜像（托管 Harness 版本下拉，复用 `GET /images`）；模型连接（从工作空间「模型连接」**多选**：首个为默认——发布时注入 `LLM_BASE_URL/LLM_MODEL/LLM_API_KEY` env（key 自动路由服务 Secret），全部勾选连接在 Agent 就绪后异步下发至其 `/models` 接口，见 §8.4）；描述。**Token 配额不做**（评审决议 §13）；
- **2 文件配置**：系统提示词必填，即**生成包**的 AGENTS.md 正文；页面实时预览将要生成的 OAF 包结构（frontmatter 由表单字段自动派生；SOUL.md、脚本等创建后于包编辑器补充）；
- **3 Agent 资产配置**：勾选式配置并**写入生成包**（区块顶部展示已选计数）：Skill（**从「Skill 管理」空间技能资产勾选**——候选列表 = 本空间 active 技能资产 + 版本下拉（默认 latest 已发布版；可选历史发布版，见 §8.4.1 版本机制）→ 生成包 frontmatter `skills` 声明 + 复制该版本目录进包 `skills/<name>/`，引用记快照版本；「+ 上传新技能」快捷入口跳 Skill 管理上传）、SubAgent（名称+职责逐条添加，或从「集成 Agent」已注册端点勾选 → `subAgents`，远程形态带 `endpoint`）、工具/MCP（**从「工具管理」已保存的 MCP 连接器勾选 → 发布时注入** `mcpServers` + `mcp-configs/`；三态权限取连接器默认策略，细调走包编辑器）；凭证/知识库「规划中」；托管 Harness 内置 Shell/文件工具无需添加，运行时三层裁决不变；
- **4 资源配置**：规格三档 1c2g / 2c4g / 4c8g（映射 Deployment requests/limits，与 DEPLOYMENT_TEMPLATE overlay 互斥保护，见 §13 决议 4）；副本数（1/2/3）；「最大实例数 / 实例最大 Session 数 / Session TTL」一期置灰（远期 HPA/运行时治理）。**创建后的规格变更以运维侧为主**（用户侧创建时选档，后续调整走运维侧，避免双侧同改）；
- **5 沙箱配置（OpenSandbox）**：「启用沙箱」开关（默认关，未开时资源配置区置灰）+ 沙箱镜像 / 超时（分钟）/ 内存上限（MB）/ CPU 核数四个资源项；OpenSandbox Server / API Key 为平台级配置（运维侧「默认配置」页维护），页面仅提示来源、API Key 经服务 Secret 注入不落包。开关写入生成包 frontmatter `config.sandbox.enabled`（随包版本走，三层裁决见 §8.4 沙箱配置页）；SANDBOX_* 资源项作为发布 env 注入；
- **6 网络配置**：「发布对外访问入口」开关（开=执行既有 Ingress/router 发布语义并展示 Endpoint；关=仅集群内可访问）；VPC 不适用置灰。

提交（头部或表单底部「立即创建」）= `POST /console/workspaces/:ws/agents`（body 为表单）→ 后端先**生成 OAF 包**：按表单组装 AGENTS.md（frontmatter identity/metadata/model/config + 系统提示词正文）与 skills/mcp-configs 目录 → 打 zip 登记 `oaf_packages` v1（落 PVC，与上传包同构）→ 再组装既有 Publish（包解析 → K8s 对象 → 异步 A2A 注册），状态机与事件时间线完全复用。**不新增发布链路语义；创建页 = 包生成器 + 发布 API 的用户面包装**。

生成包映射（表单字段 → OAF 产物；frontmatter 派生规则已评审确认，见 §13 决议 5）：

| 表单字段 | OAF 产物 |
|---|---|
| 名称 / 描述 | frontmatter `name` / `description`；`vendorKey` 固定平台命名空间（如 `agentmanager`）；`agentKey`/`slug` 由名称 slugify 自动派生（小写 + 连字符），与服务 short 名、K8s `k8s_name` 同源生成，重名校验复用发布链路（空间内包名唯一 + 集群 k8s_name 唯一） |
| 系统提示词 | AGENTS.md 正文（标准段落骨架：Agent Purpose / Core Responsibilities / Capabilities / Communication Style） |
| 模型连接 / 模型 | **不进包**：默认连接经发布 env 注入（`LLM_BASE_URL/LLM_MODEL/LLM_API_KEY`，key 路由服务 Secret）；全部勾选连接经下发接口写入 Agent `model_config`；frontmatter `model` 段仅写默认模型名（展示语义，key 永不落包） |
| Skill 勾选 | frontmatter `skills` 声明（name/source/version）+ **复制所选技能版本的 `skills/<name>/` 目录进包**（copy-on-write 自包含）；**生成包本身即引用快照**（「Agent 使用情况」按 services.package_id 解析，§8.4.1 决议 7）；技能中心后续发版不影响已生成包，升级经编辑页重新勾选 |
| SubAgent 添加 | frontmatter `subAgents`（vendor/agent/role/delegations；从「集成 Agent」勾选的端点写入 `endpoint`，认证头经 env 不落包） |
| 工具/MCP 勾选 | frontmatter `mcpServers`（vendor/server/version/configDir）+ `mcp-configs/<server>/config.yaml`（内容由「工具管理」连接器配置注入生成） |
| 沙箱开关 | frontmatter `config.sandbox.enabled`（不落包时运行时三层裁决回退：env 显式 > 包声明 > 默认 false）；SANDBOX_IMAGE / SANDBOX_TIMEOUT_MINUTES / SANDBOX_MEMORY_MB / SANDBOX_CPU_COUNT 作为发布 env 注入（不进包） |
| 规格 / 副本 / 网络 | **不进包**：作为 Publish 请求参数（replicas / resources 档 / Ingress 语义） |

生成 v1 即版本链起点。存量演进的主入口 = **编辑页**（表单级，下节）；包编辑器（运维侧，文件级）保留为高级入口，两者共用 copy-on-write 版本链。

### 8.2.1 编辑 Agent（对标 AgentCore 编辑页）

`/console/agents/:id/edit`：与创建页**同构的单页分区表单**（1 基本信息 / 2 文件配置 / 3 Agent 资产配置 / 4 资源配置 / 5 沙箱配置 / 6 网络配置），预填当前值。与创建页的差异点：

- **无「选择创建方式」卡片**（编辑固定托管 Harness 形态，对标 AgentCore `?kind=harness`）；标题「编辑 Agent」+ 右上/表单底部「保存」（无「上一步/下一步」）；
- **名称锁定**：K8s short 名创建后不可变（disabled + 说明）；显示名修改同步至新版本包 frontmatter `name`；
- **保存 = 生成包新版本（copy-on-write 派生 vN+1，复用包在线编辑链路）→ republish**：提示词 / Skill（重新勾选技能资产版本，复制新版本目录进包）/ SubAgent / MCP / **沙箱开关（包声明）** 变更经 OAF reload **热生效**；镜像 / 规格 / 副本 / **SANDBOX_* 资源 env** 变更随重发布**滚动重启生效**（对标 AgentCore「模型/提示词/Skill/SubAgent/MCP 热更新；实例规格等需重启」语义）；事件时间线新增 republish 记录，版本 Tab 出现新版本行；
- **模型连接变更**（联动 M5）：新默认连接经 env 更新（UpdateEnv）生效；新勾选连接自动下发 `/models`；移除的连接不自动删远端（保守，模型连接页可管理）；
- **入口**：Agent 详情头部「编辑」（主按钮）、版本 Tab 当前版本行「编辑」、列表卡片「编辑」；member 只读不可编辑（owner/admin）。

### 8.3 Agent 详情（Tab 化）

- **概览**：状态/Pod 实时表/Endpoint/事件时间线（复用服务详情数据面）；头部动作「编辑（主按钮，跳 §8.2.1 编辑页）/ 重发布 / 下线（danger）」，member 只读隐藏动作；
- **调试**：对话 Playground（复用发布助手页 SSE 全链：HITL 确认卡、附件、durable 续传），`AGENT_BASE=/agent/{short}`，`X-User-Id`=当前用户，会话历史按 userId 隔离；模型下拉对接会话级切换（`POST /threads/chat {model}` / `PATCH /threads/{id}`，session-model-switch-design.md 已实施）；
- **会话**：`GET /threads?userId=u-{id}`（经业务 Pod 直连）+ 回放/删除；
- **技能**：本 Agent 当前包内的技能清单（随包只读，含锁定的技能资产版本 `{name, skillId, version}`）+「管理技能」入口跳 Skill 管理页；变更经编辑页资产配置区重新勾选（生成新版本包）；
- **版本**：包版本历史 + 当前版本行「编辑」（跳 §8.2.1 编辑页）+ 历史版本行「升级到此版本」（复用 republish 到指定包版本）；
- **调用凭证**：本 Agent 的 API Key 列表与调用示例（curl A2A/HTTP，二期网关生效说明）。

### 8.4 资产管理（工作空间维度）

- **集成其它 Agent**（AGENT 构建 → 集成 Agent）：管理本空间可作为远程子 agent 的集成关系（Agent Protocol：对端开启 `agentscope.agent-protocol.enabled` 暴露 `POST /tasks` 等端点）；登记 lead→member 关系、端点 URL、流式/ask 策略与认证（`X-Agent-Protocol-Token` 经 env 注入，不落包）；创建页资产配置区勾选后写入生成包 `subAgents[].endpoint`，运行时 `agent_spawn` 调度、子事件回流父流（travel-fulfillment-agent-protocol-design.md）；
- **模型连接**（对标 AgentCore 模型管理 add-connection，与 MCP 连接器同构的「先配置、后下发」模式）：**工作空间级连接 CRUD**（backend 自有表：name/modelId/provider/baseUrl/apiKey/temperature/maxTokens/timeout；apiKey 落库内网明文、接口掩码回显）+ **连通性测试**（backend 直连 baseUrl 最小 completion）+ **下发**（发布时勾选的默认连接注入 `LLM_*` env；就绪后全部勾选连接异步同步到 Agent 的 `POST/PATCH /models`，幂等、状态可查）+ **Agent 使用情况展示**（每 Agent：已同步哪些连接 / 默认模型 / 同步状态：pending·synced·failed）；调试对话模型切换数据源 = 该 Agent 自己的 `/models`（含下发连接 + 系统默认）；
- **Skill 管理（2026-10-19 重设计：空间技能资产中心，对标 AgentCore Skill 管理 + Skill 广场 + 版本管理）**：
  - **页面三 Tab**：
    - **我的 Skill**（空间技能资产列表）：表格/卡片（技能名 mono / 描述 / 标签 / 来源徽标 market·zip·markdown / 最新版本 + 发布状态徽标 / 启用状态 / 被 N 个 Agent 引用 / 更新时间）；行操作「详情 / 编辑 / 停用·启用 / 删除（无引用时）」；顶部「创建 Skill」按钮 → 创建抽屉；搜索按名称/描述/标签过滤；
    - **Skill 广场**（对标 AgentCore Skill 广场，**多源市场预留**）：顶部市场源切换（一期仅「平台精选」内置源；`skills/market/sources` 返回，registry 外部源远期开放——目录协议/认证/zip 校验待接入真实市场时单独评审，导入安装语义与源解耦）；市场技能卡片（名称/描述/标签/下载量/**来源徽标**）+「详情 / 导入」——导入 = 一键复制为本空间技能资产（v1.0.0 published，`POST skills/market/:source/:key/install`），导入后出现在「我的 Skill」可勾选；
    - **Agent 使用情况**（2026-10-19 新增第三 Tab）：工作空间内**全部 Agent 的技能使用总览**，数据源 = 每个 Agent **最后生成的 OAF 包**（services.package_id 当前指向版本）——包内 skills/ 目录扫描（事实来源）+ frontmatter `skills` 声明（意图/版本），与沙箱配置页同构（请求期解析、无新表无后台任务）；表格列：Agent（名称+运行状态徽标）/ 当前包版本 / 技能数 / 技能清单（`name · version` 标签流，声明与目录扫描差异条目带「仅声明/仅目录」角标）/ 包生成时间 / 行操作「查看详情」；筛选：仅看有技能引用 / 关键字（Agent 名、技能名）；行「查看详情」→ 单 Agent 抽屉（`skill-usages/:agentId`）：包版本链信息（当前版本/派生自/生成时间）+ 该包技能完整清单表（技能名/包内路径/SKILL.md 描述/声明状态/匹配的技能资产与版本/合规 issues——与 SpaceSkills issues 同口径）+「换绑技能版本」入口（跳编辑页资产配置区）；存量/导入包（无技能资产对应）正常列出，matchedSkillId 为空显示「包内自带」；
  - **创建 Skill（抽屉，两种方式对标 AgentCore 创建页）**：
    - **上传技能包**：zip 文件（布局三种均支持并归一化存储：根 `SKILL.md`（标准技能包 + 可选 `_meta.json`）/ 单层目录 `<dir>/SKILL.md` / `skills/<name>/SKILL.md`；可选 `scripts/`、`references/`、`assets/`；≤20MB / ≤2000 条目，与 OAF 包上传同护栏）；上传即跑 `checkSkillDirs` 合规检查，warnings 明细回显（宽松不阻断）；解析 SKILL.md frontmatter 自动回填 name/description（可改）+ 标签；
    - **通过 Markdown 定义**：技能名（`^[a-z0-9]([a-z0-9-]*[a-z0-9])?$`、≤64 字符）+ 描述（≤1000）+ Markdown 正文（SKILL.md 内容，frontmatter name/description 自动生成）+ 标签；
    - 两种方式都落 v1.0.0 工作区草稿 →「保存并发布」直接 published（一期不接安全护栏审核，状态机预留 review 槽位，见 §6.3）；
  - **详情抽屉（版本管理）**：基本信息区（名称/描述/标签/启用开关/来源/创建时间）+ **版本列表**（版本号/状态徽标 draft·published·offline/变更说明/创建时间/操作：发布·下线·下载 zip·基于此版本创建新版本）+ **引用 Agent 列表**（每个 Agent 的名称/状态/锁定的版本——按 Agent 查询技能使用的入口）+「下载」按钮（当前发布版 zip 导出）；
  - **启用/停用**（对标 AgentCore 状态开关）：停用后创建/编辑 Agent 的候选列表不再出现该技能（已发布 Agent 的包内容不回变——包自包含）；
  - **版本机制**（对标 AgentCore skill-version-management，一期裁剪）：版本号语义化（major/minor/patch 手填，创建新版本默认 patch+1）；**正式版本不可变**，修正走「基于此版本创建新版本」；latest 语义由「最新 published 版」承担（勾选候选默认选中项）；版本下线前详情页校验引用（有 Agent 引用锁定该版本时提示迁移后再下线）；
  - **按 Agent 查询**：Skill 管理页第三 Tab「Agent 使用情况」（本页内聚，替代早先独立视图 c/skill-usages）+ 技能详情页引用列表（技能 → Agent）双向可查；Agent 详情「技能」Tab 同源展示（同一包解析聚合）；
  - 修改口径沿用 2026-10-17 决议：页面直接管理**技能资产**（新增/版本/启停/删除）；**包内 skills/ 目录**仍只读展示（包自包含副本，由生成包/包编辑器维护）；运行时分级（L1-L4）不在此页管理；
- **工具管理（MCP）**：MCP 连接器配置的创建与维护（对标 AgentCore tools/create：名称/传输/Endpoint/认证/工具范围，保存为工作空间级配置，backend 自有表）；Agent 创建或更新时在资产配置步勾选，发布时注入生成包（`mcpServers` + `mcp-configs/`）；已发布 Agent 的运行时工具枚举与三态权限（allow/ask/deny）只读展示；
- **沙箱配置**：空间内 Agent 的 OpenSandbox 沙箱启用状态**只读总览**（Agent / 沙箱状态 / 生效来源：发布 env `SANDBOX_ENABLED` 或包声明 `config.sandbox.enabled` 或默认关闭 / 资源规格：镜像·内存·CPU / 服务状态）+ 平台沙箱资源默认值掩码回显（OPENSANDBOX_SERVER_URL / SANDBOX_TIMEOUT_MINUTES 等，运维侧「默认配置」页维护）。修改不在此页：跳转对应 Agent 编辑页的「沙箱配置」区块（§8.2 / §8.2.1）。生效裁决为 agent-framework SandboxRuntime 三层：部署 env 显式设置 > 包 frontmatter `config.sandbox.enabled` > 默认关闭；
  - **实装口径（2026-10-19）**：`GET /console/workspaces/:ws/sandbox?keyword=` 返回 `SandboxItem[]`（serviceId/displayName/k8sName/status/enabled/source(env|package|default)/packageDeclared/envExplicit/image/timeoutMinutes/memoryMb/cpuCount），按 services 行 env_json + 当前包 AGENTS.md frontmatter 聚合，无新表；创建/编辑表单 `sandbox` 字段：开关写包 frontmatter（显式关必须落 `enabled: false`——copy-on-write 沿用底包时不落声明会回退旧 true），SANDBOX_IMAGE/TIMEOUT/MEMORY/CPU 作发布 env（负值护栏归零不注入），`SANDBOX_ENABLED` 恒不经 env 注入（env 显式值会永久压过包声明，与 platformconfig 模板排除该键同因）；存量服务 env 已有的 `SANDBOX_ENABLED` 显式值在编辑页回显为表单显式值，保持 sticky 不自动迁移。
- **知识库**：占位页（空态 + 「规划中」说明，对标 AgentCore「知识库经 MCP 接入」）；
- **记忆空间（2026-10-19 重设计，对标 AgentCore 记忆空间「列表页 + 详情页三 Tab」）**：
  - **概念映射**：AgentCore 的记忆空间（MemoryStore）是承载长期记忆与会话历史的独立资源；本平台**不新建实体——每个运行中 Agent 即一个记忆空间**。长期记忆 = agent-framework `agent_fs` KV（`MEMORY.md` 索引 + `memory/YYYY-MM-DD.md` 按日文件，SDK Memory flush（对话事实抽取）/ consolidation（合并进 MEMORY.md）自动沉淀，业务面零改动）；会话历史 = `session_user` + `session_message` 归档（`AGENT_HISTORY_ARCHIVE_ENABLED` 默认开启，压缩摘要以 compaction_summary 条目出现在历史中）。两者均按 `agents/{agent}/users/{uid}` 隔离（记忆文件），会话按 userId 过滤；
  - **页面结构（一期纯只读，前端直连业务 Pod，不经 backend）**：
    - **列表页** `assets/memory/page.tsx`：空间卡片网格（对标 AgentCore 记忆空间列表）——卡片 = 空间内运行中 Agent（名称/运行状态徽标/描述/更新时间），数据源 `GET /console/workspaces/:ws/agents`（既有端点聚合，无新表）；卡片「查看记忆」→ 详情页；空态提示「会话沉淀后展示记忆」；
    - **详情页**（Agent 维度，`assets/memory/[id]/page.tsx`）**三 Tab**（对标 AgentCore 详情页「概览/长期记忆/会话历史」）：
      - **概览**：基本信息（Agent 名称/运行状态/记忆启用状态——`AGENT_MEMORY_ENABLED` 默认 true、存储位置说明）+ 记忆文件清单（MEMORY.md + memory/*.md，路径/大小/更新时间，数据源 `GET /agent/{short}/debug/memory`）；
      - **长期记忆**：按用户分组的记忆内容只读展示——MEMORY.md 正文（Markdown 渲染）+ memory/ 按日文件内容；用户切换（当前登录用户默认选中，仅可见 `X-User-Id` 自己的会话数据面返回的分组）；**不做编辑/删除/语义检索**（远期，检索需 AgentCore SearchMemories 类语义索引，当前 `memory_search` 为 SDK 内置关键词级）；
      - **会话历史**：会话列表（标题/最近活跃/消息数，`GET /agent/{short}/threads?userId=u-{me}`）→ 点击展开会话消息（角色+内容，`GET /agent/{short}/threads/{sid}/history`，压缩摘要渲染为「上下文已压缩」分隔条）；
  - **数据通路与红线**：全部直连 `/agent/{short}/*` 数据面（proxy 注入 `X-User-Id`，与调试对话同构），backend 不代理、不落库——记忆数据不进控制面（原则一）；`/debug/memory` 无用户过滤（调试面端点，返回全部分组），前端仅渲染当前用户分组 + 全部用户仅显示分组名（脱敏口径，后端代理过滤列二期）；
  - **保留期如实标注**：会话清理（每日 3 点，`SessionCleanupService`）按 `updated_at` 保留 7 天且对 `agent_fs` **无 namespace 豁免**——记忆文件 7 天不活跃同样会被清理；页面以提示条标注（「记忆文件随会话数据按保留期清理（默认 7 天不活跃）；延长保留期属运行时配置，远期提供」）；
  - **不做**（对标 AgentCore 未开放的能力一并裁剪）：记忆写入/编辑/删除、语义检索（SearchMemories）、Scope/Metadata 业务标签、空间 CRUD/配额、记忆 MCP 服务部署（挂载语义不适用——本平台记忆随 Agent 生而启用，`AGENT_MEMORY_ENABLED` 控制）、多空间挂载路由；
- **凭证管理**：API Key 签发（明文仅展示一次）/列表（前缀识别）/吊销/过期时间；
- **包可见性**（评审决议 7）：公共包（workspace_id=0）所有空间可见；各工作空间的包按空间隔离，空间包仅本空间可见，导入/派生在本空间包列表内进行。

### 8.4.1 Skill 中心补充决议（2026-10-19）

| # | 议题 | 决议 |
|---|---|---|
| 1 | 技能资产的独立实体化 | 新建 `skills/skill_versions/skill_releases/skill_market_items` 四表（§6.3）；技能不再是 OAF 包附属清单的只读投影——「无法上传技能，业务无法闭环」的缺口即在此：此前技能只能经包生成间接产生 |
| 2 | 创建方式 | 两种（对标 AgentCore 创建页裁剪）：**上传技能包（zip）** 与 **通过 Markdown 定义**；Nacos CLI 不适用本平台不做；市场技能经「Skill 广场 → 导入」获得（等同安装） |
| 3 | 版本与 Agent 绑定 | **最后生成的 OAF 包即引用快照**（frontmatter `skills` 声明 + 复制的版本目录），生成包自包含；技能中心发版不自动影响已发布 Agent（对标「按具体版本号引用始终不变」），升级经编辑页重新勾选；不实现 latest 别名实体（「最新 published 版」承担默认勾选语义）与自定义别名（AgentCore 也未开放） |
| 4 | 审核流 | 一期不接 AI 安全护栏（独立产品），发布直接 published；状态机预留 review/pending 槽位 |
| 5 | 引用查询 | 双向：`skill-usages`（按 Agent 列技能）+ `skills/:id/usages`（按技能列 Agent）；删除技能前校验引用（有引用 409） |
| 6 | 可见性 | 一期技能随工作空间隔离（空间内可见可用），不做 AgentCore 的私有/公开开关；跨空间共享经 Skill 广场（市场目录平台运营维护） |
| 7 | Agent 使用情况数据源 | **解析每个 Agent 最后生成的 OAF 包**（services.package_id 当前版本：skills/ 目录扫描 + frontmatter skills 声明），与沙箱总览同构（无新表、无后台任务、请求期实时）；`services.skills_json` 是 A2A agent-card 注册产物（register.go），语义不同不混用；独立视图 c/skill-usages 收敛为 Skill 管理页第三 Tab |
| 8 | Skill 广场多源 | `skill_market_sources` 源注册表 + 条目 `source_id` 归属；API 带 `:source` 段与 `?source=` 形参；一期仅内置源（builtin，镜像内 zip）；registry 外部源只建模型不开放——目录协议（认证/条目 schema/zip 校验/下载配额）待接真实市场时单独评审；导入后技能资产 `source` 恒记 market，另有 `sourceName` 展示来源名 |

### 8.5 观测大盘（一期不做）

指标与 Trace 后续统一从可观测系统接入（OTel），本期内不做大盘页与数据聚合：一期仅保留导航占位（标注「规划中」）与 eval-studio（18400）外链入口。概览页统计只保留控制面可得的数据（Agent 总数/运行中/异常）。

### 8.6 身份与权限（用户侧视角）

工作空间成员管理（邀请既有用户/改角色/移除）+ 个人中心（改密/API Key 总览）。账号集成（SSO/OIDC）列远期。

## 9. 运维侧调整

1. **页面迁移**：现有 8 页整体迁入 `/admin/*` 路由组，交互与视觉不变（一期保留原 URL 重定向，e2e 不感知）；顶栏加「运维侧/用户侧」切换入口（admin 专属）。
2. **新增页面**：
   - **工作空间管理**：列表（名称/slug/owner/成员数/Agent 数/状态）、创建、停用、配额查看、空间内 Agent 视图与「纳管」操作；
   - **用户管理**：列表（用户名/角色/状态/最近登录/所属空间）、创建、改角色、禁用、重置密码；
   - **平台概览**（可选）：全局服务状态汇总、最近事件（聚合 service_events）。
3. **发布向导微调**：新增可选「归属工作空间」下拉（admin 发布时即可指定归属，免二次纳管）。
4. **现有 API 冻结**：`/api/v1/services|packages|...` 保持现状；运维侧前端可渐进切换到 `/api/v1/admin/*` 新端点，二期再收敛旧端点鉴权。

---

## 10. 前端信息架构与工程改造

### 10.1 路由组（App Router）

```
src/app/
├── login/page.tsx                  登录（无守卫）
├── (console)/                      用户侧 —— 侧边栏布局（对标 AgentCore）
│   ├── layout.tsx                  顶栏（工作空间切换器/通知/用户菜单）+ 左侧导航
│   ├── console/page.tsx                    概览
│   ├── console/agents/page.tsx             Agent 列表
│   ├── console/agents/create/page.tsx      创建页（单页分区表单）
│   ├── console/agents/[id]/edit/page.tsx   编辑页（同构表单预填，保存 = 新版本 + republish）
│   ├── console/agents/[id]/page.tsx        详情（Tab）
│   ├── console/assets/{models,skills,tools,sandbox,knowledge,memory,credentials}/page.tsx
│   ├── console/observability/page.tsx      观测入口
│   └── console/settings/page.tsx           成员与个人中心
├── (admin)/                        运维侧 —— 沿用现有顶栏布局
│   ├── layout.tsx
│   ├── admin/page.tsx                      服务列表（原 /）
│   ├── admin/publish|services/[id]|packages/*|settings（原页迁移）
│   ├── admin/workspaces/page.tsx           工作空间管理（新）
│   └── admin/users/page.tsx                用户管理（新）
```

- `middleware.ts`：读 `oaf_session` cookie，未登录跳 `/login`；`/admin/*` 额外校验 role（JWT payload 前端可解，仅作 UX 守卫，权威裁决在 backend）。
- 现有页面向 `(admin)` 迁移时**逻辑零改动**（复制目录 + import 路径调整），一期加 `/:path` → `/admin/:path` 重定向保 e2e。

### 10.2 复用与新增

- 复用：`lib/api.ts`（扩展 auth/console/admin 三组方法 + 401 统一跳登录）、SSE 流处理与 HITL 组件（发布助手 → 抽成 `components/chat/*` 共享）、`FileTreeView`、`Markdown`、`icons.tsx`、状态徽标 map（顺手收敛到 `lib/status.ts`，消除现有两处重复）。
- 新增：`WorkspaceSwitcher`、`AuthGuard(middleware)`、`StatCard`、`CreateForm`（分区表单骨架）、`ApiKeyDialog`。
- 状态管理维持现有 useState+轮询风格（不引库）；暗色/组件库本期不引入，保持 Tailwind 原生风格 + 蓝色主色，视觉对齐 AgentCore 的「左导航 + 卡片内容区」。

### 10.3 proxy.ts 扩展

- `/agent/*` 通配反代（现在仅 `release-agent` 硬编码）：按 `{short}` 解析目标 `oaf-{short}-svc`（与 platform-router 同规则），注入 `X-User-Id`；
- 开发环境新增 `/login` 免代理直出。

---

## 11. 架构原则核对清单

| 条目 | 结论 |
|---|---|
| 启动路径零 reconcile（原则一） | ✅ 迁移只写平台自有 MySQL 表；K8s 写仍仅经显式发布 API |
| 业务资源生命周期不绑平台对象 | ✅ 不引入 ownerReferences/finalizer |
| 业务运行时不依赖 backend | ✅ 对话/文件/协议流量直连业务 Pod；唯一新增依赖是二期 router auth_request（元数据子请求，fail 策略可配，且 backend 本就是发布链路必需组件） |
| 多副本无状态（原则二） | ✅ 用户/工作空间/API Key 全落 MySQL；JWT 无状态；frontend 无会话粘性 |
| 每服务独立 checkpoint 库/Redis 前缀 | ⚠️ 维持现状缺口（平台不自动派生 `CHECKPOINT_JDBC_URL`/`AGENT_REDIS_PREFIX`），本次拆分不改变该结论，工作空间 ≠ checkpoint 隔离域；运维手册继续显式配置 |
| agent-framework 零改动 | ✅ 全部经既有端点与 `X-User-Id` |
| CI 门禁 | 新页面进 frontend-ci（lint+build）；backend 新增表/中间件单测进 backend-ci；e2e 现有四必需 job 不受影响（存量 API/URL 不变） |

---

## 12. 实施分期

**P0 身份与拆分骨架（先行合入，约 backend 8 文件 + frontend 布局层）**
- backend：users/workspaces/members/api_keys 模型与 AutoMigrate、幂等回填、auth 三端点 + JWT 中间件、admin/users + admin/workspaces 端点、admin 鉴权中间件；
- frontend：`/login`、middleware 守卫、双路由组骨架、现有页迁入 admin 组 + 重定向、运维侧「用户管理/工作空间管理」两页；
- 验收：登录后 admin 可进双侧；存量 e2e 全绿（URL 重定向生效）。

**P1 用户侧控制台主体**
- console API 全量（§7.2）+ services/packages 挂 workspace 回填收尾；
- 用户侧五页群：概览 / Agent 列表 / 创建页 / Agent 详情（概览+调试+会话 Tab）/ 资产六页（知识库占位）；
- proxy `/agent/*` 通配 + `X-User-Id` 注入；
- 验收：普通用户在工作空间内创建（表单生成包 + 导入包两种方式）→ 调试 → 下线，全程不进运维侧。

**P2 网关与治理增强**
- router auth_request + introspect + API Key 数据面生效；
- 审计聚合页、规格档完善（视依赖）；观测大盘整体后移（指标/Trace 从可观测系统接入，单独设计），一期仅占位 + eval-studio 外链；
- 存量 API 收敛进 `/api/v1/admin/*`（破坏性变更，单独评审 + e2e 同步改）。

**P3 远期池**：知识库、Team 协作 UI、workspace 级模型配置、HPA/Session 治理、SSO、观测大盘（可观测系统指标/Trace 接入）、Token 计量与配额。

---

## 13. 评审决议（2026-10-16 已确认，原开放问题全部关闭）

| # | 议题 | 决议 |
|---|---|---|
| 1 | 登录方式 | 一期仅用户名+密码（SSO/OIDC 列远期） |
| 2 | 默认工作空间可见性 | 存量服务回填进默认空间且对普通用户不可见，需 admin 显式纳管/分配——符合预期 |
| 3 | 数据面鉴权节奏 | 一期接受「控制台内身份可信、直连仍开放（与今天同暴露面）」；二期上 router 网关鉴权（auth_request + introspect + API Key 数据面生效） |
| 4 | 创建页「规格三档」 | 以 Deployment resources 直译实现，与 DEPLOYMENT_TEMPLATE overlay 机制互斥保护；**创建后的规格变更以运维侧为主**（用户侧创建时选档，后续调整走运维侧） |
| 5 | frontmatter 派生规则 | `vendorKey` 固定平台命名空间；`agentKey`/`slug` 由 Agent 名称 slugify 自动生成，与 K8s short 名及发布重名校验同源；生成 v1 即版本链起点，后续经包在线编辑 copy-on-write 派生（细化为 §8.2 生成包映射表） |
| 6 | 用户自注册 | 不开放，仅 admin 建号分派 |
| 7 | 包与工作空间归属 | 公共包（workspace_id=0）所有空间可见；**各工作空间的包按空间隔离**（空间包仅本空间可见，导入/派生在本空间包列表内进行） |

---

## 14. 前端设计 Demo

配套可交互原型（单文件 HTML、零外部依赖、双击浏览器直接打开；hash 路由切换页面）：
[docs/design/demo/console-user-workspace-demo.html](demo/console-user-workspace-demo.html) —— 覆盖登录页（开发者/管理员两种角色入口）、用户侧（概览 / Agent 列表 / 创建页（单页分区表单：选择创建方式卡片 + 六个编号区块——基本信息 / 文件配置 / 资产配置（Skill 勾选 = 空间技能资产 + 版本下拉 + 版本快照语义）/ 资源配置 / **沙箱配置（启用开关 + 镜像/超时/内存/CPU，未启用时置灰）** / 网络配置，右上/底部「立即创建」→ 生成 OAF 包并发布；导入/纳管为次要方式）/ **编辑页（创建页同构表单预填，沙箱开关预填包声明状态，保存 = copy-on-write 新版本 + republish）** / Agent 详情六 Tab（含 SSE 调试对话与 HITL 确认卡示例）/ 模板 / 集成 Agent（Agent Protocol 远程子 agent）/ 工具管理（MCP 配置创建与注入）/ **沙箱配置（空间内 Agent 沙箱启用状态只读总览 + 生效来源三层裁决展示）** / **Skill 管理（2026-10-19 重设计：三 Tab——我的 Skill / Skill 广场（多源市场预留：源切换器 + 来源徽标）/ Agent 使用情况（按各 Agent 最后生成 OAF 包解析的技能使用总览，行「查看详情」抽屉展示包版本与技能完整清单）**，创建抽屉两方式「上传技能包 zip / Markdown 定义」，技能详情抽屉含版本管理与引用 Agent 列表）** / 模型连接（配置/下发/使用展示）/ 资产管理其余页（**记忆空间（2026-10-19 重设计，对标 AgentCore）：空间列表（Agent 卡片）+ 详情页三 Tab 概览/长期记忆/会话历史，纯只读直连数据面**）/ 成员管理 / 观测大盘占位（一期不做））、运维侧（平台概览 / 服务列表（现状风格 + 工作空间列）/ 配置包 / 工作空间管理 / 用户管理 / 审计 / 默认配置），左侧导航分组与 AgentCore 控制台存档同构。

> Demo 为视觉与交互定稿用；实现以本文 §10 的 Next.js 工程结构为准。

### 14.1 实装截图（2026-10-17 部署验证实拍）

实施完成后按 demo 对照逐页实装，实拍截图见 **[../screenshots/console-split/README.md](../screenshots/console-split/README.md)**（24 张，1440×900，kind 集群 backend v14 / frontend v11）：
登录页（01）→ 用户侧骨架与概览（02）→ Agent 列表（03）→ 创建页单页分区表单（04，对照本节 §8.2 五区块）→ 模板（05）→ 集成 Agent（06）→ 模型连接列表与右侧抽屉（07/08）、工具管理连接器表与新建抽屉（09/09-assets-tools-drawer）（**交互演进：新建/编辑类表单从 demo 右栏常驻改为 slide-over 抽屉**）→ Skill（10，口径修订：包配置技能清单，去 L4）→ 记忆空间（11，2026-10-19 重设计：空间列表 + 详情三 Tab，对标 AgentCore）→ 凭证（12）→ 成员（13）→ 观测/知识库占位（14/15）→ Agent 详情六 Tab（16–19）→ 运维侧服务列表/工作空间/用户/审计/概览（20–24）。

---

## 15. 分模块实施文档

本设计已按功能模块拆分为可独立开工/验收的实施文档集：[../implementation/](../implementation/)（索引、完成定义（DoD：单测 + e2e 双绿才算完成）、e2e 落位表与依赖图见其 [README.md](../implementation/README.md)）：

| 模块 | 文档 | 范围 |
|------|------|------|
| M0 | [01-auth-skeleton.md](../implementation/01-auth-skeleton.md) | 四表 + JWT + 登录页 + 双侧布局骨架与存量页迁移（P0） |
| M1 | [02-admin-governance.md](../implementation/02-admin-governance.md) | 运维侧治理：工作空间/用户/审计/纳管（P0） |
| M2 | [03-console-foundation.md](../implementation/03-console-foundation.md) | console 骨架 / 概览 / Agent 列表 / 切换器 / proxy 通配 |
| M3 | [04-agent-create.md](../implementation/04-agent-create.md) | 创建页单页表单 + OAF 包生成器 + 复用发布 |
| M4 | [05-agent-detail.md](../implementation/05-agent-detail.md) | Agent 详情六 Tab（含调试对话复用件抽取） |
| M5 | [06-model-connection.md](../implementation/06-model-connection.md) | 模型连接配置/测试/下发/使用展示 + 会话级切换 |
| M6 | [07-mcp-connectors.md](../implementation/07-mcp-connectors.md) | MCP 连接器 CRUD 与发布注入 |
| M7 | [08-agent-integration.md](../implementation/08-agent-integration.md) | 集成 Agent（Agent Protocol 声明管理） |
| M8 | [09-workspace-assets.md](../implementation/09-workspace-assets.md) | Skill 中心（市场/上传/版本/引用查询）/ 记忆空间（Agent 即空间：列表 + 详情三 Tab 只读）/ API Key / 模板 / 成员 / 占位页 |
| M9 | [10-gateway-auth.md](../implementation/10-gateway-auth.md) | P2 网关鉴权（auth_request + introspect + API Key 生效） |

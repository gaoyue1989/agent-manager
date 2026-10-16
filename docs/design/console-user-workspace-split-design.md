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
   - **用户侧**（console，对标 AgentCore）：面向 Agent 开发者/业务用户，以**用户**登录、以**工作空间**隔离资源，提供 Agent 构建（创建/纳管/调试/会话）、资产管理（模型连接/Skill/工具/记忆/凭证）、观测入口。
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
| Agent（托管 Harness） | `services`（OAF 包 + agent-framework 镜像发布） | 发布链路完整 | 用户侧创建向导 = 发布 API 的用户面包装 |
| Agent（自定义代码/镜像） | `services`（自定义 image + OAF 包约定） | 已支持任意镜像 | 向导「导入模式」直选镜像 |
| 纳管 Agent | 存量服务挂 workspace 归属 | 无归属字段 | 运维/管理员把无主服务纳管进工作空间 |
| Agent 模板 | 精选 OAF 包（打 `template` 标记） | 包体系已有 | 包列表加模板标记，创建向导提供模板入口 |
| 模型连接 | agent-framework `/models`（model_config 表） | 已有 CRUD+test | 用户侧页面只读选用（一期平台级） |
| Skill 管理 | `/skills`（L2/L3 包级）+ `/skills/users`（L4 用户级） | 已有完整管理面 | 用户侧 Skill 页对接 L4 + L2/L3 只读 |
| 工具管理（MCP） | OAF 包 mcp-configs + `/tools` `/mcp` 枚举 | 已有 | 用户侧工具页只读展示；自定义连接器远期 |
| 知识库 | —— | 无 | 仅占位入口，标注「规划中」（经 MCP 接入同 AgentCore 思路） |
| 记忆空间 | agent-framework 记忆/会话（session_user / debug memory） | 调试面已有 | 一期只读：按会话查看记忆摘要；写管理远期 |
| 凭证管理 | API Key（调用 agent 的凭证）+ 敏感 env Secret 化 | Secret 路由已有、API Key 无 | 新建 `api_keys` 实体与签发/吊销页 |
| 调试对话（Playground） | `/threads/chat` SSE + HITL | 发布助手页已打通全链 | 复用为 Agent 详情「调试」Tab（按 agent 直连） |
| 会话管理 | `/threads` + `/subscribe` + history | 已有 | Agent 详情「会话」Tab（userId=当前用户） |
| 观测大盘 | eval-studio（18400）/ OTel | 飞轮已有独立服务 | 一期入口外链 + 汇总卡；内嵌大盘远期 |
| 资源配置（规格/Session/实例数） | replicas + DEPLOYMENT_TEMPLATE 资源档 | replicas 已有 | 规格三档（1c2g/2c4g/4c8g）后端内置映射；min=replicas，max/Session TTL 远期（HPA 不在本期） |
| 网络配置（公网/VPC） | Ingress 发布（router/host 双模式） | 已有 | 向导「网络」步=是否发布对外访问入口 |
| Token 配额 | —— | 无 | 占位开关（不生效），远期接计量 |
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

### 6.3 回填策略（migrate.go，幂等）

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
POST /api/v1/console/workspaces/:ws/agents         创建并发布（body 为发布向导产物，见 §8.2；内部复用 Core.Publish）
GET  /api/v1/console/workspaces/:ws/agents/:id     详情
POST /api/v1/console/workspaces/:ws/agents/:id/{unpublish,publish,republish}
DELETE /api/v1/console/workspaces/:ws/agents/:id   （仅 owner/admin）
GET  /api/v1/console/workspaces/:ws/templates      模板包列表（oaf_packages 公共包 + template 标记）
GET  /api/v1/console/workspaces/:ws/models         模型连接只读（透传 agent-framework /models）
GET  /api/v1/console/workspaces/:ws/skills         L2/L3 只读 + 本工作空间 L4 聚合
GET  /api/v1/console/workspaces/:ws/tools          工具/MCP 枚举（透传 /tools /mcp）
GET  /api/v1/console/workspaces/:ws/memory/:agent  记忆/会话摘要只读（一期）
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

工作空间维度统计卡（Agent 总数 / 运行中 / 异常）+ 最近发布时间线 + 快捷入口（创建 Agent / 打开调试 / 观测大盘外链）。数据来自 `console/workspaces/:ws/summary`。

### 8.2 Agent 列表与创建向导

列表：统计卡 + 卡片网格（对标 AgentCore `pageSize=12` 卡片流）+ 状态筛选；卡片含名称/状态徽标/模型/最近更新；操作：调试、详情、下线/上线。顶部按钮「创建 Agent」「纳管 Agent」（纳管仅在有可纳管存量服务时出现）。

创建向导五步（对标 AgentCore 五步表单，逐项落地）：

| 步 | AgentCore 原型 | 本平台一期实现 |
|---|---|---|
| 1 基本信息 | 托管 Harness / 自定义镜像 / 快速创建 | 创建方式三选：**从模板创建**（精选 OAF 包）/ **导入 OAF 包**（上传或选公共包）/ **纳管已有服务**（管理员场景）。Harness 选择 → 运行镜像（默认托管镜像 + 版本下拉，复用 `GET /images`）；名称/描述；模型连接（复用平台默认或从 `/models` 选） |
| 2 提示词与文件 | 系统提示词 / SOUL.md·AGENTS.md | OAF 包规范承载：模板方式→向导内编辑提示词生成包副本（copy-on-write，复用包在线编辑链路）；导入方式→只读预览包 frontmatter 与 AGENTS.md |
| 3 资产配置 | Skill/SubAgent/工具/知识库/记忆/凭证 | 只读预览包声明的 skills/tools/mcp（OAF 三层裁决不变，不在向导里改运行行为）；用户级技能（L4）勾选注入；知识库/凭证显示「暂无可用，规划中」 |
| 4 资源配置 | 规格/Session/TTL/实例数 | 副本数（1/2/3）；规格三档 1c2g / 2c4g / 4c8g（后端内置资源档映射进 Deployment requests/limits，作为 DEPLOYMENT_TEMPLATE 等价的显式参数，不走 overlay）；「实例最大 Session 数 / Session TTL / 最大实例数」一期置灰标注远期（对应 HPA/运行时治理，不做半吊子开关） |
| 5 网络配置 | 公网/VPC | 「发布对外访问入口」开关（开=执行既有 Ingress/router 发布语义并展示 Endpoint；关=仅集群内可访问）。VPC 不适用置灰 |

提交 = `POST /console/workspaces/:ws/agents` → 后端组装既有 Publish（包解析 → K8s 对象 → 异步 A2A 注册），状态机与事件时间线完全复用。**不新增发布链路语义，只加归属与入口。**

### 8.3 Agent 详情（Tab 化）

- **概览**：状态/Pod 实时表/Endpoint/事件时间线（复用服务详情数据面）；
- **调试**：对话 Playground（复用发布助手页 SSE 全链：HITL 确认卡、附件、durable 续传），`AGENT_BASE=/agent/{short}`，`X-User-Id`=当前用户，会话历史按 userId 隔离；
- **会话**：`GET /threads?userId=u-{id}`（经业务 Pod 直连）+ 回放/删除；
- **技能**：L2/L3 只读 + 调试页「用户技能」区块对接；
- **版本**：包版本历史 + 升级发布（复用 republish 到指定包版本）；
- **调用凭证**：本 Agent 的 API Key 列表与调用示例（curl A2A/HTTP，二期网关生效说明）。

### 8.4 资产管理（工作空间维度）

- **模型连接**：一期只读（平台级模型配置列表 + 连通性测试结果），新增/修改引导到运维侧或后续 workspace 级配置；
- **Skill 管理**：L4 用户技能 CRUD（直连 `/skills/users`）+ 包级技能只读；
- **工具管理**：MCP server / 工具枚举只读 + 每工具三态权限（allow/ask/deny）展示；
- **知识库**：占位页（空态 + 「规划中」说明，对标 AgentCore「知识库经 MCP 接入」）；
- **记忆空间**：按 Agent×会话只读查看记忆/摘要；编辑与清除策略远期；
- **凭证管理**：API Key 签发（明文仅展示一次）/列表（前缀识别）/吊销/过期时间。

### 8.5 观测大盘

一期：外链 eval-studio（18400）+ 工作空间内 agent 状态/事件汇总卡。二期：OTel 指标内嵌（平台已具备 DEPLOYMENT_TEMPLATE 注入 OTel 的能力）。

### 8.6 身份与权限（用户侧视角）

工作空间成员管理（邀请既有用户/改角色/移除）+ 个人中心（改密/API Key 总览）。账号集成（SSO/OIDC）列远期。

---

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
│   ├── console/agents/create/page.tsx      创建向导（5 步）
│   ├── console/agents/[id]/page.tsx        详情（Tab）
│   ├── console/assets/{models,skills,tools,knowledge,memory,credentials}/page.tsx
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
- 新增：`WorkspaceSwitcher`、`AuthGuard(middleware)`、`StatCard`、`Wizard`（五步骨架）、`ApiKeyDialog`。
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
- 用户侧五页群：概览 / Agent 列表 / 创建向导 / Agent 详情（概览+调试+会话 Tab）/ 资产六页（知识库占位）；
- proxy `/agent/*` 通配 + `X-User-Id` 注入；
- 验收：普通用户在工作空间内创建（模板+导入两种方式）→ 调试 → 下线，全程不进运维侧。

**P2 网关与治理增强**
- router auth_request + introspect + API Key 数据面生效；
- 观测大盘（eval-studio 跳板 + OTel 汇总）、审计聚合页、Token 配额与规格档完善（视依赖）；
- 存量 API 收敛进 `/api/v1/admin/*`（破坏性变更，单独评审 + e2e 同步改）。

**P3 远期池**：知识库、Team 协作 UI、连接器市场、workspace 级模型配置、HPA/Session 治理、SSO。

---

## 13. 开放问题（请评审确认）

1. **登录方式**：一期仅用户名+密码是否可接受？（SSO/OIDC 已列远期）
2. **默认工作空间可见性**：存量服务回填进默认空间且对普通用户不可见、需 admin 显式纳管/分配——是否符合预期？还是存量服务直接全员可见？
3. **数据面鉴权节奏**：一期接受「控制台内身份可信、直连仍开放（与今天同暴露面）」、二期才上 router 网关鉴权，还是必须一期就上？（影响 platform-router 配置与 backend introspect 的排期）
4. **用户侧创建向导的「规格三档」**：以 Deployment resources 直译实现，是否需要与 DEPLOYMENT_TEMPLATE overlay 机制互斥保护？
5. **用户自注册**：是否开放用户自主注册（默认进个人空间），还是仅 admin 建号分派？
6. **包与工作空间的归属**：公共包（workspace_id=0）所有空间可见是否足够，还是包也要严格空间隔离？

---

## 14. 前端设计 Demo

配套可交互原型（单文件 HTML、零外部依赖、双击浏览器直接打开；hash 路由切换页面）：
[docs/design/demo/console-user-workspace-demo.html](demo/console-user-workspace-demo.html) —— 覆盖登录页（开发者/管理员两种角色入口）、用户侧（概览 / Agent 列表 / 创建向导五步（含三种创建方式切换）/ Agent 详情六 Tab（含 SSE 调试对话与 HITL 确认卡示例）/ 模板 / 资产管理六页 / 观测入口 / 成员管理）、运维侧（平台概览 / 服务列表（现状风格 + 工作空间列）/ 配置包 / 工作空间管理 / 用户管理 / 审计 / 默认配置），左侧导航分组与 AgentCore 控制台存档同构。

> Demo 为视觉与交互定稿用；实现以本文 §10 的 Next.js 工程结构为准。

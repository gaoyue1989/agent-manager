# 基于 DeepSeek Harness 插件重建 agent-framework 对外功能 — 调研与设计

| | |
|---|---|
| 状态 | **M0 探针已实施并通过验收**（2026-10-02，实施记录见附录 C；实现位于独立仓库 [gaoyue1989/agent-framework-dsh](https://github.com/gaoyue1989/agent-framework-dsh)，已自主仓迁出） |
| 编制时点 | 2026-09-29 |
| 调研对象 | [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)（下称 **dsh**），master@2026-09-28，npm `@deepseek-ai/dsh` 最新 `0.1.7-rc.2` |
| 对标物 | [agent-framework](../../agent-framework/AGENTS.md)（Java/AgentScope，下称 **AF**）的**对外功能契约** |

---

## 0. 结论（TL;DR）

**能实现。** dsh 的"一切皆插件"架构（Cordis 微内核）为 AF 的每一项对外能力都提供了官方扩展点，不存在硬性阻断项；其中约一半能力（工具体系、HITL 审批、Plan Mode、上下文压缩、技能、子 Agent、MCP、多会话并发、会话标题、OTel）dsh **已内置或有直接等价物**，其余（OAF 包加载、HTTP/SSE 对外面、MySQL 持久化、多租户隔离、durable SSE 多副本、远程沙箱、A2A/Agent Protocol 服务端）需要**自建插件**，工作量集中在自建部分。

但有三个必须正视的前置条件：

1. **dsh 处于 developer preview**：README 明示"THERE WILL BE COMPATIBILITY-BREAKING CHANGES"；npm 自 2026-08-10 首发至今 7 周发了 28 个版本，全部是 `0.x-rc/alpha`。版本锁定 + 升级跟进是长期成本。
2. **多租户是最大架构落差**：dsh 的产品形态是"单人 Harness"（一台机器一个 Home 一个用户），AF 是"多租户服务运行时"（userId 隔离 State/文件/记忆/技能）。dsh 的 seam（fs provider、按 Agent 的 scoped 注册、storage key、session 命名）足以实现隔离，但需要我们**自己铺设全部 userId 传递与隔离层**，dsh 不提供任何现成的租户概念。
3. **持久化/多副本需整体自建**：dsh 会话日志的落库后端（SessionPersistence seam 明确支持 out-of-tree provider）与跨副本事件流（Redis Streams）都由我们实现，AF 现有的 MySQL 表结构经验可以直接平移，但不能复用代码。

**建议**：不替换现有 Java 运行时；以"平行运行时"立项（新模块 `agent-framework-dsh/`），M0 探针验证关键链路后再决策是否推进。总成本估算约 **12–16 人周**（单人全职 3–4 个月），详见 §9。

---

## 1. dsh 调研摘要

### 1.1 基本信息

| 项 | 值 |
|---|---|
| 定位 | 开源 Agent Harness（"Everything is a Plugin"），DeepSeek AI 出品 |
| 实现 | TypeScript / Node.js，基于 [Cordis](https://github.com/cordiverse/cordis) 插件框架 |
| 许可 | MIT |
| 状态 | developer preview（快速迭代，承诺会有破坏性变更） |
| 发布 | npm `@deepseek-ai/dsh`（CLI），monorepo 内 200+ 包均发布为 `@deepseek-ai/dsh-*` |
| 运行形态 | `dsh web`（Web UI :3080）/ `headless`（一次性）/ `sdk`（stdio JSON-RPC）/ `sdk-minimal` / `acp`（自动化协议 stdio） |

### 1.2 组合模型（Profile / Bundle / Plugin）

- 运行中的 dsh = 启动期按序组装的**插件树**。**Profile** 是命名的组合（`web`/`headless`/`sdk`/`acp`…），列出其堆叠的 **Bundle**（一组 Cordis 配置行 + 补丁文件）。
- 自定义 profile 极简：一个 `package.json` 声明 `dsh.profile.bundles: ["@deepseek-ai/dsh-base"]`，再叠加自己的 `cordis.patch.yml`；`dsh plugin --profile <name> add <package>` 安装 out-of-tree 插件。
- **`dsh-base`** 是共享底层（模型接入、工具全集、durable 会话、默认权限策略：文件写限制在工作区 + 高危操作 ask），`sdk-app`（base + stdio JSON-RPC server）就是我们这类"服务化组合"的官方范本。
- 每个插件是一个 Cordis plugin（`apply(ctx)`），一切注册都是可逆 effect——插件卸载时自动回滚。

### 1.3 与本设计相关的扩展点（均已核实）

| 扩展点 | 机制 | 对 AF 的意义 |
|---|---|---|
| HTTP 路由 | `dsh-host-webserver`：插件注册 named `exact`/`prefix` 路由 + upgrade 路由 + 唯一 fallback；`host: 0.0.0.0` 支持，无 TLS/鉴权（路由属主自管） | **对外 REST/SSE 面的落点**，AF 全部端点可注册 |
| 协议驱动 | "protocol driver" 范式（官方 worked example = `dsh-acp`）：`ctx.agents.create/resume` + `followup()/steer()/cancel()` + `session/event`/`agent/assistant-stream` 观测 + `AgentHandle.dispose()`；**单进程多会话并发**（ACP 明示） | `/threads/chat`、A2A、Agent Protocol 的实现范式 |
| 会话模型 | Session = **append-only 事件日志**（`SessionEventMap`，可声明合并扩展自定义事件类型）；LLM 历史**派生**自日志；`ctx.sessions` 内存态 | history / 回放 / 事件流的数据底座 |
| 持久化 seam | `ctx.sessionPersistence`：`create/open/stat/list` + `SessionHandle`（`read/append/flush/close`），进程内单写者（重复 write-open 抛 `SessionAlreadyOwnedError`）；官方明示 **out-of-tree provider 可实现同契约**（默认 JSONL+zstd，带代际/迁移链） | **MySQL 落库的接缝** |
| 工具体系 | `ctx.tools.register()/restrict()`；waterfall `tools/pre-execute`（返回 allow/deny/**ask**）/`tools/execute`/`tools/post-execute`/`tools/result` | 工具注册、deniedTools 过滤、HITL 三态、审计 |
| 审批 | `tools/pre-execute` 返回 `ask` → 经 `ctx.approval` 应答（ACP 即"一次性机器应答者"实现） | **HITL confirm 链路的官方接缝** |
| LLM 适配 | `ctx.llm` 适配器注册表；`dsh-llm-pi-ai` 支持 **OpenAI 兼容网关**（自定义 baseURL + `apiKeyEnv` 凭据引用，免重启生效）+ 多 provider 路由 | `LLM_BASE_URL/KEY/MODEL_ID` 契约直接映射；按会话选模型（ACP `session/set_config_option` 已验证） |
| MCP | `dsh-mcp-client`：每 server 一条配置（stdio / streamable-http，headers、重连、fail-soft 默认），工具名 `mcp__{server}__{tool}`；`mcp-resources` 提供资源读取 | MCP 注册面；per-call 用户 header 需自建（见 §6.6） |
| 沙箱 | `ctx.sandbox` = **同内核**进程隔离（Linux bwrap/Landlock）；官方明示"容器/微 VM/远程执行器**不是**注册为 sandbox backend，而是**整体替换 fs + subprocess 的执行世界**"（架构文档：pointing them at a remote sandbox moves Bash, PTY, and LSP with them） | OpenSandbox 集成的正确路径 = 自定义 fs/subprocess provider |
| 文件系统 | `ctx.fs` provider + `fs/*` 事件（策略拦截） | 多租户工作区/远程文件落点 |
| 内置能力 | Plan Mode（`dsh-plan-mode`）、上下文压缩（`ctx.compaction` + `compaction-basic`/工具结果裁剪）、技能（section + tool 注册、文件系统技能库）、子 Agent（`ctx.subagents` provider 注册表，含 in-process spawn）、记忆（官方模式：section provider + tool，**无成品包，需自建**）、会话标题（`ctx.sessionTitle`，含 LLM 生成 provider）、todo/goal/workflow、OTel（`ctx.otel` + session-telemetry） | 与 AF 功能表几乎一一对位 |
| 存储 | `ctx.storage`（非会话数据，json/sqlite 后端）+ `ctx.attachments`（durable 二进制附件） | 自有表（confirm_context/model_config/…）的落点 |
| 热重载 | 配置级 HMR（web profile）+ Cordis effect 卸载；MCP 配置行原地编辑即热更新连接 | `/admin/reload` 语义的映射基础 |

### 1.4 官方"功能→机制"对照（摘自 extension-cookbook，证明微内核主张可查证）

每项产品功能都映射到已文档化的扩展点监听器：权限系统/AskUserQuestion = `tools/pre-execute` 返回 ask + `ctx.approval` 应答；Plan Mode = 独立包；压缩 = `ctx.compaction` seam；AGENTS.md = section provider；MCP = 每 server 一插件；技能 = section + tool 注册；记忆 = section provider + tool；定时任务 = 插件注册工具 + `followup`；UI = `agent/assistant-stream`（活流）+ `session/event`（持久结算）。**不修改循环本体即可实现全部产品功能**——这是"能否实现"问题最硬的正面证据。

---

## 2. 对标：AF 对外功能契约盘点

按 `agent-framework/AGENTS.md` 与 `docs/api-frontend-sse.md` 归纳的**外部可见面**（不论内部实现）：

| # | 契约域 | 内容 |
|---|---|---|
| C1 | HTTP 服务面 | 30+ 端点（/、/health、/metadata、/threads 系列、/files、/skills 系列、/models 系列、/mcp、/tools、/debug、/admin/reload、/.well-known/agent-card.json、MCP Apps 代理等），端口 `SERVER_PORT`(8100) |
| C2 | SSE 对话流 | `POST /threads/chat` 单次流（唯一对话入口）；帧词表：`session_created`/`waiting`/`TEXT_BLOCK_*`/`THINKING_*`/`TOOL_CALL_*`/`TOOL_RESULT_*`/`tool_call_summary`/`permission_ask`/`file_ready`/`AGENT_END`/`error`/`done` |
| C3 | durable SSE | `GET /threads/{sid}/subscribe?afterSeq=` 断连续传/回放 + `/status` 五态（working/completed/waiting_confirm/idle/interrupted）；多副本 |
| C4 | OAF v0.8.0 包 | `AGENT_CONFIG_DIR`(/config，只读 PVC)：AGENTS.md frontmatter（提示词、permission 三态、deniedTools、sandbox.enabled）、`skills/`(L2)、`mcp-configs/{server}/config.yaml`（connection/auth/permissions/userHeaders/ui）、`tools.json`、`subagents/*.md` |
| C5 | 动态 reload | `POST /admin/reload?scope=auto\|mcp\|agent`，指纹分流，生效=下一轮对话 |
| C6 | HITL | permission ask → `confirm_context` → `POST /threads/{sid}/confirm[-stream]`；跨副本确认 |
| C7 | 多租户 | `userId`（`X-User-Id` 网关注入 / A2A metadata / Channel 参数）隔离 State/文件/记忆/技能；会话列表按用户 |
| C8 | A2A v1.0.0 | `POST /` JSON-RPC 全量透传（message/send、message/stream、tasks/*）+ agent-card；已知限制：A2A 通道不支持 ask 工具 |
| C9 | Agent Protocol | `/tasks*` 远程子 agent 服务端（token 认证、SSE、resume），默认关闭 |
| C10 | 持久化 | MySQL（oaf_checkpoint）：agent_state、agent_fs、session_message、session_event、session_user、confirm_context、tool_audit_log、file_asset、turn_lease、ui_context、model_config；Flyway 迁移纪律 |
| C11 | 多副本互斥 | Turn 租约（同 session 串行，排队 waiting 帧） |
| C12 | 文件 | 上传/下载（file_asset，local/s3 后端）、`present_file`/`present_url` 工具、`file_ready` 帧、代理下载白名单 |
| C13 | MCP Apps | `ui://` 资源代理、`ui_context` 静默注入、卡片工具调用代理、app_only/只读语义 |
| C14 | 模型面 | `/models` CRUD + 连接测试 + 会话级模型切换 + provider 方言（openai/glm/deepseek…） |
| C15 | 记忆/技能 | MEMORY.md + memory/（按用户）；技能目录（声明∪目录）+ 用户 L4 管理端点 + @Skill 解析 |
| C16 | 沙箱 | OpenSandbox 远程容器（USER 级复用、工作区投影、记忆/技能回写、守卫租约） |
| C17 | 运维面 | OTel 追踪、LLM 调用记录（/llm-calls）、工具审计、会话清理、健康检查、调试页 |
| C18 | 环境变量契约 | `LLM_*`、`CHECKPOINT_*`、`AGENT_REDIS_*`、`SANDBOX_*`、`OPENSANDBOX_*`、`FILE_*`、`AGENT_*`（见 AF AGENTS.md 环境变量表）；平台保留键注入 |
| C19 | 平台集成 | K8s Deployment + Ingress（`/agent/{name}`）、就绪后 agent-card 注册、Secret 路由敏感键 |

---

## 3. 逐项可行性映射

图例：✅ 直接等价/内置　🟡 自建插件、seam 已备　🔴 自建插件、工作量大或语义需重构

| 契约 | dsh 机制 | 等级 | 说明 |
|---|---|---|---|
| C1 HTTP 面 | `dsh-host-webserver` 路由注册 | 🟡 | 每端点一个 route handler；无 TLS/鉴权与 AF 一致（鉴权在宿主 nginx / 自定义 filter） |
| C2 SSE 帧 | 协议驱动：`agent/assistant-stream`（活流 chunk）+ `session/event`（持久结算：`tool/call`/`tool/result`/`assistant/message` 嵌入完整流） | 🟡 | 帧序列化器纯映射工作；thinking 增量在 dsh 流词表中存在（ACP 透出 thoughts）；`file_ready` 由自定义工具 `present_file` 合成（与 AF 现状同构） |
| C3 durable SSE | `session/event` 监听 → Redis Streams（XADD）+ tailer 回放 | 🟡 | AF 的 SessionEventBus/Store/Tailer 设计整体平移为插件；`afterSeq` ↔ 事件 seq（dsh 日志天然有连续 seq） |
| C4 OAF 包 | 自建 `oaf-loader` 插件：frontmatter 解析 → `ctx.systemPrompt.section()` + `ctx.tools.restrict()` + MCP 配置合成 + 技能目录注册 + subagent 声明 | 🟡 | SnakeYAML 换 JS yaml；`config.permission.tools` 三态映射到 `tools/pre-execute` 规则；`userHeaders`/`ui` 等 AF 特有字段由自建 MCP 桥消费（见 §6.6） |
| C5 reload | 配置行原地替换（MCP 官方支持热更新连接）+ HMR + 插件 effect 卸载重挂 | 🟡 | `auto` 指纹分流逻辑在 loader 内重实现；"生效=下一轮"语义天然满足（配置行热替换，不打断进行中 turn） |
| C6 HITL | `tools/pre-execute` 返回 `ask` → 挂起等待 `ctx.approval` 应答；桥接插件把 ask 转成 `permission_ask` 帧落 `confirm_context`，`/confirm` 端点应答 | ✅ | 官方设计好的接缝（ACP 就是这么做的）；跨副本确认靠 C3 事件流 + C11 租约 |
| C7 多租户 | session 命名空间 `{tenantPrefix}:{sessionId}`（沿用 AF 键格式）+ 自建 `user-fs` provider（按 userId 命名空间）+ 记忆/技能插件按 userId 取数 | 🔴 | **最大改造面**：dsh 无租户概念，userId 全链路（HTTP → agent 创建 → fs/memory/skill/MCP header）由我们铺设；好处是隔离点集中（见 §6.4） |
| C8 A2A | 自建 `a2a-server` 插件（POST / 路由 + JSON-RPC 分发 + SSE），task 快照从会话日志构造 | 🟡 | AF 的 `MySqlTaskStore`（读日志构造、save no-op）思路平移；A2A 通道不支持 ask 的限制按同语义声明 |
| C9 Agent Protocol | 自建 `agent-protocol` 插件（/tasks* 路由 + token filter + TaskRecord 落库 + resume） | 🟡 | 与 C8 同范式；token 校验是普通 route 前置逻辑 |
| C10 持久化 | 自建 `mysql-session-persistence`（实现 `SessionPersistence` 契约）+ 自有表（storage seam 或直连 mysql2） | 🔴 | 事件日志落 MySQL（seq 连续、torn-tail 语义、flush 屏障、代际头）；AF 表结构经验可平移，代码不可复用；Flyway 纪律换为迁移脚本表（§7.2） |
| C11 Turn 租约 | Redis SET NX 租约插件（AF `TurnLeaseStore` 平移）+ dsh 进程内单写者（`SessionAlreadyOwnedError`）双层 | 🟡 | dsh 进程内互斥已有；跨副本互斥补 Redis 层 |
| C12 文件 | 自建 `file-asset` 插件（`ctx.attachments` 或直建）+ `present_file/present_url` 注册为 `ctx.tools` | 🟡 | local/s3 双后端、代理白名单照搬 AF 设计 |
| C13 MCP Apps | `ctx.mcpResources` 读资源 + 自建代理路由（CSP 注入、ui_context 表、PreCall 注入 → dsh 对应 `agent/pre-step`/`system-prompt` 注入） | 🟡 | 纯 HTTP 面工作；`ui_context` 注入点 = `agent.inject()`（官方：注入上下文等待唤醒消息）或 system prompt section |
| C14 模型面 | `dsh-llm-pi-ai` 路由表（OpenAI 兼容 baseURL）+ `/models` 端点 + 会话选模型（ACP `set_config_option` 模式） | 🟡 | 方言（glm/deepseek 思考参数下发位置）在 pi-ai/provider 层配；托管模型存自有表 |
| C15 记忆/技能 | 记忆：自建插件（section provider + `memory_*` 工具 + 按 userId 存储）；技能：文件系统技能库（`/config/skills`）+ L4 管理端点自建 | 🟡 | 记忆官方无成品但有明确模式；L4 语义（回落/栅栏/墓碑）为 AF 自有设计，端点行为照搬 |
| C16 沙箱 | **替换执行世界**：自建 OpenSandbox `ctx.fs` + `ctx.subprocess` provider（官方文档指定的远程沙箱路径）；投影/回写/守卫为插件逻辑 | 🔴 | dsh 自带 sandbox 仅同内核隔离，不适合容器化多租户部署；OpenSandbox 协议 AF 已趟通，TS 重写 |
| C17 运维面 | `ctx.otel` + `tools/result` 审计批量写 + `llm/stream` 观测记 llm-calls + 清理定时任务 | 🟡 | 全部有对应事件/服务 |
| C18 环境变量 | 启动器：env → profile patch/config 合成（Cordis 配置支持 `!!js process.env.X` 表达式） | 🟡 | 纯胶水 |
| C19 平台集成 | 自定义镜像（node + dsh + 本仓库插件包）+ 同 env/探针契约；agent-card 端点自建 | 🟡 | 平台侧零改动（发布的是"另一种运行时镜像"的 OAF 服务） |

**统计**：✅ 1 项（HITL 恰好是接缝最完善的）、🟡 14 项、🔴 3 项（多租户、MySQL 持久化、远程沙箱）。🔴 项全部有明确的接缝与先例，风险在于工作量与细节语义，而非可行性。

---

## 4. 目标架构

### 4.1 组件图

```
┌────────────────────────── K8s Pod（agent-framework-dsh，Node.js :8100）──────────────────────────┐
│                                                                                                  │
│  dsh --profile oaf-web                                                                           │
│  ├─ dsh-base（模型接入 pi-ai 路由 / 工具全集 / 默认权限策略 / Plan Mode / 压缩 / 技能 / 子Agent） │
│  ├─ dsh-host-webserver ── route 表（全部 AF 端点）                                                │
│  │                                                                                               │
│  ├─ ★ 本仓库插件族（packages/oaf-runtime/*）                                                      │
│  │   ├─ oaf-loader          OAF 包解析装配（frontmatter→prompt section / restrict / MCP 合成）     │
│  │   ├─ oaf-server          HTTP/SSE 对外面（threads/chat、subscribe、files、skills、models…）     │
│  │   ├─ hitl-bridge         tools/pre-execute ask ⇄ confirm_context ⇄ /confirm 端点               │
│  │   ├─ a2a-server          POST / JSON-RPC + agent-card + task 快照                              │
│  │   ├─ agent-protocol      /tasks* + token filter（默认关闭，env 门控）                           │
│  │   ├─ mysql-persistence   ctx.sessionPersistence 的 MySQL provider（事件日志）                   │
│  │   ├─ redis-event-bus     session/event → XADD；subscribe tailer（durable SSE 多副本）           │
│  │   ├─ turn-lease          Redis SET NX 跨副本 turn 互斥（waiting 帧）                            │
│  │   ├─ user-fs             按 userId 命名空间的文件 provider（→ agent_fs 型 KV 表）               │
│  │   ├─ memory              MEMORY.md + memory/（section + memory_* 工具，按 userId）             │
│  │   ├─ skill-admin         /skills/users/* L4 管理 + @Skill 合并                                 │
│  │   ├─ file-asset          上传/下载/present_file/present_url（local/s3）                        │
│  │   ├─ mcp-bridge          mcp-configs/config.yaml → MCP 连接（含 userHeaders per-call 注入）     │
│  │   ├─ opensandbox-world   OpenSandbox fs+subprocess provider（投影/回写/守卫）                   │
│  │   └─ ops                 OTel / llm-calls / 工具审计 / 会话清理 / reload 端点                   │
│  │                                                                                               │
│  └─ 数据层：oaf-mysql（会话日志 + 自有表） · oaf-redis（事件流 + 租约） · /config PVC（只读）       │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
        ▲ 平台（backend/frontend）零改动：同 env 契约、同 Ingress、同 agent-card 注册时序
```

（★ = 自建；其余为 dsh 官方包。）

### 4.2 仓库形态

```
agent-framework-dsh/            # 新顶层模块（与 agent-framework 平行，不侵入）
├── package.json                # dsh.profile.bundles: [dsh-base, @oaf/oaf-bundle]
├── pnpm-workspace.yaml
├── packages/
│   ├── oaf-bundle/             # 组合补丁（webserver + 本仓库插件挂载顺序）
│   └── oaf-runtime/            # 上表插件族（每插件一包，遵循 dsh-plugin 规范）
├── e2e/                        # 移植 AF e2e（同场景断言，Node 侧编排）
└── Dockerfile                  # 多阶段：pnpm build → node 运行时（含 pinned dsh 版本）
```

### 4.3 会话与身份映射（关键决策）

| AF 概念 | dsh 映射 |
|---|---|
| `sessionId`（A2A/Channel 会话） | `SessionId`（brand string），存储键沿用 `{tenantPrefix-标准化}:{sessionId}` 与 AF 现网数据**键级兼容**（见表 §7.1 说明） |
| `userId` | Agent 创建时的自有 meta（`meta.user`）+ 全链路显式传参；不依赖 dsh 任何用户概念 |
| AgentState checkpoint | dsh 会话事件日志（append-only）；`/history` 从日志派生（AF 的 session_message 归档对应 dsh 日志天然全量留存，压缩事件入日志，压缩前历史天然可查——**比 AF 的双源合并更简单**） |
| `replyId`/turn | dsh `turn` 序号 + 派生 replyId（`turn` 粒度，与 AF 帧字段对齐） |
| 一次对话（/threads/chat） | `ctx.agents.create/resume` → `followup(userMessage)` → 观测至 `turn/end`（settlement = Agent idle）→ 流关闭；Agent 常驻或按 LRU dispose（对齐 AF 的 checkpoint 常驻语义，默认常驻 + 清理任务兜底） |

---

## 5. 关键链路设计

### 5.1 `/threads/chat`（C1+C2）

1. route handler 解析 `{message, userId, sessionId, fileIds, model}`；无 `sessionId` 则生成 UUID 并先发 `session_created` 帧。
2. `turn-lease`：Redis SET NX 抢租约，失败进入排队（`waiting` 帧，超时 120s `error`）——平移 AF 语义。
3. 文件注入：`fileIds` → 工作区物化 + 消息内容块（AF `UploadWorkspaceInjector` 语义）。
4. 会话模型：`model` 非空 → 绑定会话（自有表）并本 turn 生效（请求级 provider/model 覆盖，ACP 已验证此模式）。
5. `ctx.agents.create({ sessionId, seed })` 或 resume；`followup(createUserMessage(...))`。
6. 帧序列化：订阅该 Agent 的 `agent/assistant-stream`（chunk → `TEXT_BLOCK_DELTA`/`THINKING_*`）与 `session/event`（`tool/call`/`tool/result` → `TOOL_CALL_*`/`TOOL_RESULT_*`/`tool_call_summary`）；`present_file` 工具结果 → `file_ready`。
7. settlement：`turn/end`（reason 对齐）→ `AGENT_END`；HITL 挂起（§5.3）→ `permission_ask` 后**不关流等待**或按 AF 语义释放租约挂起。
8. 全量事件同步 XADD（§5.2），单次流本身即事件源之一。

### 5.2 durable SSE 与多副本（C3+C10+C11）

- **事件总线**：`redis-event-bus` 监听 `session/event` → `XADD oaf:events:{sessionId}`（带自增 seq）；与 AF `SessionEventStore` 同构，`afterSeq` 续传语义照搬。
- **接管**：副本 A 崩溃后，副本 B 的 `/subscribe` 从 Redis 回放（afterSeq 起）+ 若 turn 未完成则由 `turn_lease` TTL 到期后的副本冷恢复会话（`sessionPersistence.open` 重放日志重建 Agent）——对应 AF kill 接管 E2E 场景。
- **写入屏障**：`SessionHandle.flush` 在 durability checkpoint（dsh-session-checkpoint-policy 策略包）触发；MySQL provider 的 `append` 直接落库（事务）+ `flush` 为物化屏障，语义满足 seam 契约。
- **单写者**：进程内由 dsh 保证（`SessionAlreadyOwnedError`）；跨副本由 Redis 租约兜底（双保险，避免两副本同时 write-open）。

### 5.3 HITL（C6）

```
tools/pre-execute(ask)                       dsh 官方接缝
  └─ hitl-bridge：构造 AF permission_ask 载荷（tool_call_id/参数摘要/选项）
       ├─ 写 confirm_context（CAS 防重复，AF 表结构）
       ├─ 事件总线 XADD（permission_ask 帧）→ 本地流直发
       └─ 挂起应答 Promise（ctx.approval 应答者注册）
POST /threads/{sid}/confirm[-stream]
  └─ 校验 confirm_context → approve/deny → resolve 挂起 Promise → dsh 恢复工具执行
       └─ approve 后新执行段重新抢租约（AF confirm-stream 语义）
```

AF 已知的 SDK 语义缺陷（HITL 恢复丢工具参数，e2e §11.3 ①）在 dsh 上是否复现需 E2E 验证——dsh 的工具参数在 `tool/call` 事件中原文留存，恢复路径不同，**有机会修复该缺陷**（列入 M2 验收项）。

### 5.4 OAF 加载与 reload（C4+C5）

- `oaf-loader` 启动时扫 `/config`：AGENTS.md frontmatter → system prompt section + 权限规则（注册 `tools/pre-execute` 规则表：MCP 显式 > frontmatter > 自动放行，Deny→Ask→Allow 顺序照搬）+ `deniedTools` → `ctx.tools.restrict()`；`skills/` → 文件系统技能库（每轮重扫语义：dsh 技能为 section+tool 注册，目录变化由 loader watcher 重新注册 effect）；`mcp-configs/` → `mcp-bridge` 建连；`subagents/*.md` → `ctx.subagents` 声明。
- `/admin/reload`：`auto` = 指纹比对（frontmatter hash / MCP 配置 hash 分开记）；MCP-only → `mcp-bridge` 原地重连（dsh MCP 配置行热替换官方支持）；整包 → loader effect 全量卸载重挂（Cordis 可逆性保证旧连接收尾）。失败回滚 = 保留旧 effect（挂载成功才切换，天然原子）。
- 平台包在线编辑（copy-on-write 新版本）→ republish → rollout 的现有时序零改动。

### 5.5 多租户隔离（C7，最大改造面）

隔离点收敛为四类，全部在插件层实现：

| 数据 | 隔离实现 |
|---|---|
| 会话日志 | 存储键含 userId 前缀（键级兼容 AF）；会话列表按 userId 查询（自有索引表） |
| 工作区文件 | `user-fs` provider：root = `agent_fs` 型 KV（按 userId 命名空间）或 PVC 子目录 + DB 元数据；所有文件工具经 `ctx.fs`（沙箱启用时整体指向 OpenSandbox，见 §5.6） |
| 记忆 | memory 插件按 userId 取数；section 注册按 Agent scope（`agent.ctx`）注入对应用户内容 |
| 技能 L4 | skill-admin 插件按 userId 命名空间（AF `agents/{agent}/users/{uid}/skills` 键格式照搬）；`/skills/available`、@Skill 解析按 `X-User-Id` 合并 |

userId 传递：HTTP 层解析（header/参数/metadata，与 AF 三来源对齐）→ Agent meta → 各插件经 Agent 上下文读取；**不依赖 dsh 全局状态**。

### 5.6 OpenSandbox（C16）

- 按官方"替换执行世界"路径：`opensandbox-world` 同时提供 `ctx.fs` provider 与 `ctx.subprocess` backend，二者指向同一远程容器（AF 已趟通的 per-USER 沙箱复用 + TTL 语义平移）；bash/文件工具全部经 seam 走沙箱，无需改工具本体。
- 投影（AGENTS.md/skills/记忆物化进容器）与回写（MEMORY.md/memory/、L4 技能 KV 回写）= 沙箱 call 结束后的同步任务（AF `WorkspaceSyncService` 语义，含 `.deleted`/`.admin-override` 两个仲裁标记）。
- 守卫：Redis SET NX 串行化同 userId 的沙箱获取（AF issue #27 方案平移）。
- 非沙箱档：`user-fs` 直连本地/PVC——两档切换与 AF `SANDBOX_ENABLED` 三层裁决（env > 包 frontmatter > false）一致。

### 5.7 A2A 与 Agent Protocol（C8+C9）

- 两个独立插件，同属"协议驱动"范式：route handler 收 JSON-RPC → `ctx.agents` 操作 → 协议帧转换。
- A2A task 快照：从会话日志派生（`MySqlTaskStore` 的"读日志构造、save no-op"思路天然适配事件溯源模型）；`message/stream` = 订阅 §5.2 事件流的另一序列化。
- Agent Protocol `/tasks*`：token filter（env 缺失 fail-fast，与 AF 一致）+ TaskRecord 落自有表 + `/resume` 走 hitl-bridge 同一确认上下文（`RemoteConfirmBridge` 是 lead 侧概念，属调用方——本运行时只实现 member 侧）。

### 5.8 无状态多副本横向扩展（补充设计，2026-09-29）

**结论：支持。** 目标形态 = "无状态 Pod + 全量状态外置"，与 AF 现网多副本模型（e2e-multi 已验证）同级。精确语义：Pod 可随意增删、任意副本可服务任意请求、**无粘性会话**、崩溃后其他副本可接管；正在执行的 turn 天然钉在持租约副本上（流式执行固有属性，非退化）。

**状态归属表：**

| 状态 | 归属 | 多副本行为 |
|---|---|---|
| 会话事件日志 | MySQL `session_log`（mysql-persistence） | append 即事务提交，跨副本立即可见（强于 JSONL 的 flush 语义） |
| 工作区/agent_fs/记忆/技能 L4 | MySQL KV（user-fs/memory/skill-admin） | 按 userId 命名空间，任意副本读写 |
| confirm_context 等自有表 | MySQL | CAS/幂等写，任意副本可处理 |
| durable SSE 事件流 | Redis Streams（redis-event-bus） | 任意副本 afterSeq 回放/续传，不依赖产生者存活 |
| Turn 写权 | Redis 租约 ⊕ dsh 进程内单写者 | 双层互斥，无双写者（见下） |
| 上传文件产物 | FILE_STORAGE_TYPE | **多副本必须 s3 或共享 PVC**（local 落 Pod 本地盘跨副本 404；AF 同约束） |
| 活 Agent/SSE/MCP 连接 | 副本本地 | 缓存与连接，非权威数据；可从日志冷恢复 |
| OpenSandbox 容器 | OpenSandbox 服务端 | USER 级复用按 userId 键控；获取串行化 = Redis 守卫租约（AF issue #27 方案平移） |

**四个机制：**

1. **跨副本写者租约**：dsh `SessionHandle` 契约明示"handle 是跨进程写租约守护的唯一入口"——进程内互斥 dsh 自带（重复 write-open 抛 `SessionAlreadyOwnedError`），跨副本互斥是 provider 职责：mysql-persistence 的 write-open = 先抢 Redis 租约，失败抛同义错误。
2. **durable SSE 不落原副本**：`/subscribe` 在任意副本从 Redis 回放，持有活 Agent 的副本另并本地实时流（AF `SessionEventTailer` 同构）。
3. **HITL 挂起全外置**：ask 时 → confirm_context 落库（CAS）→ flush 日志 → **释放租约** → Agent 挂起；confirm 落任意副本 → 抢租约 → MySQL 日志冷恢复 → 应答审批 → 续执行（AF `confirm-stream` "新执行段重新 acquire 租约"同义）。有利证据：dsh `tool/call` 事件原文留存工具参数（raw JSON string），冷恢复不依赖内存态——AF 已知"恢复丢参"缺陷在此模型下有机会不存在（M2 验收项）。
4. **崩溃接管**：持租约副本 kill → 租约 TTL 到期（心跳续租）→ 任意副本 write-open 冷恢复继续。

**边界条件（诚实清单）：**

- 执行中 turn 的 SSE 连接钉在执行副本，断开后任意副本续传（AF 现状同级）；
- 文件存储后端必须 s3/共享 PVC；
- 动态 reload 走 loader 编程式 effect 重挂，**不写本地 Home**（Pod 文件系统只读安全）；
- 定时任务（清理/标题/记忆 flush）需租约防重或 DB 幂等；
- 暖 Agent 缓存仅是缓存：扩缩容不要求粘性，冷恢复正确性由 E2E 保证（非可选优化）。

**验收口径**（M2 门禁，移植 e2e-multi R 组）：断连续传 / kill 接管 / 并发 confirm 互斥 / 事件对账 + 双副本并发 write-open 互斥注入测试。全绿即 `--replicas=N` + Service 轮询（无 session affinity）成立。

---

## 6. 数据模型与迁移

### 6.1 会话日志表（mysql-persistence）

```
session_log(
  session_id   VARCHAR(128)  -- 含 userId 前缀，键级兼容 AF agent_state.session_id
  seq          BIGINT        -- 事件序号，连续
  event        JSON          -- SessionEvent（含嵌 入流记录）
  turn         INT, step     INT
  created_at / flushed_at
  PRIMARY KEY(session_id, seq)
)
session_header(session_id PK, format_version, inherited_event_count, created_at, meta JSON)
```

- flush 屏障 = 批次事务提交；torn-tail 防御 = 事务原子性（优于 JSONL 的截断修复路径）。
- dsh 的 JSONL 代际迁移机制（vN→vN+1 链）在 DB provider 中简化为 `format_version` 字段 + 升级脚本。

### 6.2 自有表（沿用 AF schema 语义）

`session_user`、`confirm_context`、`turn_lease`（Redis，无表）、`tool_audit_log`、`file_asset`、`ui_context`、`model_config`、用户技能 KV（`agent_fs` 同构命名空间）——表结构与 AF 保持一致（列名/语义对齐，允许新库直接建 AF V1..V7 同等结构），便于双运行时**共用同一个 oaf_checkpoint 库做对照测试**。

### 6.3 迁移纪律

Flyway 不引入（Node 侧）；用轻量迁移器（如 `db-migrate` 或自写 20 行版本表）执行同一批 SQL 文件，保留 AF 的纪律条款：只新增版本、禁止改已合并版本、新机制只写新数据必须同版本回填。

---

## 7. 部署与平台集成（C18+C19）

- **镜像**：多阶段（pnpm build → `node:22-slim`），锁死 `@deepseek-ai/dsh` 精确版本（`pnpm-lock` + `--frozen-lockfile`；内网离线构建走 pnpm store 打包，对齐 AF 离线开发镜像流程）。
- **环境变量**：启动器（薄 JS 入口）读 `LLM_*/CHECKPOINT_*/AGENT_REDIS_*/SANDBOX_*` 等 → 生成 profile patch（`!!js process.env.X` 表达式仅适合简单值，复杂逻辑在启动器内合成 YAML）→ `dsh --profile oaf-web`。
- **探针**：`/health` route 直接实现（探针不打 dsh 内部）；就绪后 agent-card 由平台调用注册——端点照常返回。
- **Ingress/端口**：`SERVER_HOST/PORT` 映射 webserver 配置；平台保留键（`AGENT_CONFIG_DIR` 等）同语义。
- **CI**：新目录触发独立 workflow（lint + typecheck + vitest + 移植版 e2e-core），不并入现有六项必需检查（平行运行时不进 master 门禁，直到试点结论）。

---

## 8. 风险与缓解

| # | 风险 | 等级 | 缓解 |
|---|---|---|---|
| R1 | dsh pre-1.0 破坏性变更（7 周 28 版） | 高 | 精确锁版 + 升级专项（每 2–4 周评估一次 changelog）；插件面收敛在文档化 seam（webserver/persistence/tools/llm/fs），避免依赖内部 API；极端情况 fork 维护（MIT 允许，monorepo vendor 目录官方也在用） |
| R2 | 多租户细节遗漏（dsh 单人假设的隐性全局态） | 高 | M1 即上多用户 E2E（AF R 组用例移植）；审计清单：所有 `ctx.*` 全局服务逐个确认无跨会话串号（settings/credentials/title 等） |
| R3 | 事件语义差异导致帧序列化回归（AF 帧词表是前端契约） | 中 | 以 `api-frontend-sse.md` 帧表为规范写快照测试；e2e 录制回放架构平移（LLM mock fixtures 复用） |
| R4 | MySQL persistence provider 细节（单写者/崩溃恢复/并发 flush）不达标 | 中 | M1 专项混沌测试（kill -9 / 双副本并发 open / seq 断裂注入）；seam 契约有官方 JSONL 参照实现可对照 |
| R5 | OpenSandbox world 替换的工作量超预期（bash/PTY/LSP 全走 seam） | 中 | M3 才启动；先跑非沙箱档全量用例；AF 的协议录制件可复用 |
| R6 | 性能（Node 单进程多会话 SSE 并发） | 中 | AF 已有并发基准文档（concurrency-benchmark-plan）平移压测；ACP 多会话为官方支持路径；必要时多副本横向扩（durable SSE 已支持） |
| R7 | 双运行时并存的维护成本 | 中 | 明确"试点→决策"里程碑（M2 后评估）；不并行超过一个季度；对照测试共用库降低漂移 |

---

## 9. 实施计划与成本

| 阶段 | 内容 | 验收 | 工时 |
|---|---|---|---|
| M0 探针 | profile + webserver + 单 turn 对话 + SSE 三帧（session_created/TEXT_BLOCK_DELTA/AGENT_END）+ MySQL 落一条事件 | 冒烟脚本跑通；决策点 1： seam 手感与 dsh 版本风险确认 | 1 周 |
| M1 核心 | oaf-loader + oaf-server（threads/chat/subscribe/status/history/health）+ mysql-persistence + turn-lease + user-fs + 多用户隔离 | e2e-core 移植版（S 基础 + F 文件）绿；多用户串号审计清零 | 3–4 周 |
| M2 协议 | hitl-bridge + a2a-server + mcp-bridge（含 read_only/子集）+ redis-event-bus 多副本（断连续传/kill 接管） | e2e-core H/M/A 组 + e2e-multi 移植版绿；HITL 恢复丢参缺陷验证；决策点 2：是否立项到 M4 | 3–4 周 |
| M3 面板 | memory + skill-admin + models + file-asset + MCP Apps + reload + debug 页移植 + ops（OTel/审计/清理） | e2e 对应组绿；调试页可用 | 3 周 |
| M4 高级 | opensandbox-world（投影/回写/守卫）+ agent-protocol + 全量 e2e 对账 | e2e-sandbox/协议用例绿；与 Java 运行时同库对照跑全量回归 | 3–4 周 |

合计 **13–16 人周**（单人）；双人并行约 8–10 周。长期固定成本：dsh 版本跟进（~0.5 人日/两周）。

---

## 10. 结论与建议

1. **可行性：成立**。dsh 微内核架构的扩展点覆盖了 AF 全部对外契约，官方"功能→机制"对照表 + ACP 协议驱动范例 + 持久化 out-of-tree provider 声明构成充分证据；无阻断项。
2. **收益**：TypeScript 单代码栈（与前端/平台工具链统一）、事件溯源模型天然解决 AF 的 history 双源合并与压缩后历史问题、插件热载优于 Java SPI、HITL 有机会修复已知恢复缺陷。
3. **代价**：13–16 人周的一次性迁移 + 多租户/持久化/沙箱三大自建面 + dsh pre-1.0 的长期跟进成本。
4. **建议路径**：
   - 不动现有 Java 运行时（生产继续服役）；
   - 立项 M0 探针（1 周）验证 seam 手感与版本风险，产出可运行冒烟；
   - M2 后做"继续/终止"决策（届时核心链路 + 多副本 + HITL 已见分晓）；
   - 若走到底，以"同库对照回归"（§6.2）保证切换安全，平台与前端零改动。

---

## 附录 A：调研信息源

- 仓库：README、docs/architecture.md（组合模型/turn 流/会话日志/capability seams/功能落点表）、docs/api-gateway.md（Typert /api 与 Fetch 路由）、docs/capability-seams.md（服务清单）、docs/cookbook/extension-cookbook.md（功能→机制映射）、docs/subsystems/session.md、docs/subsystems/persistence.md（SessionHandle 契约）
- 包文档：dsh-host-webserver（路由注册）、dsh-base（组合与默认策略）、dsh-sdk-app（服务化组合范本）、dsh-acp（协议驱动范例：多会话/权限应答/模型选项）、dsh-mcp-client（MCP 配置/热更新/重连）、sandbox 组（同内核边界与远程替换路径）、workspace 组、llm 组（pi-ai OpenAI 兼容网关）
- npm：`@deepseek-ai/dsh` 版本史（0.0.1-rc.1 → 0.1.7-rc.2，2026-08-10 起）
- 本仓库：agent-framework/AGENTS.md（对外契约盘点基准）、docs/api-frontend-sse.md（帧词表）、docs/design/*（设计纪律与归档约定）

## 附录 B：明确不做的事（范围裁剪）

- 不改 backend/frontend/platform 任何代码（平行运行时对平台透明）；
- 不追求 AF 内部实现等价（AgentScope checkpoint、Flyway、Spring 装配、Java SPI 插件机制——后者被 dsh 原生插件体系取代，`e2e/plugin-echo` 场景改为 dsh 插件包等价验证）;
- 不实现 AF 已声明的死支路（如 A2A 通道 ask 支持维持"声明式限制"现状语义）；
- dsh Web UI（`dsh web` 的浏览器应用）不进入交付面——对外 UI 仍是平台 frontend + AF 调试页移植。

---

## 附录 C：M0 实施记录（2026-10-02）

**版本变更**：dsh 升至 `0.2.0-rc.2`（编制时点 0.1.7-rc.2），全部 seam 未变；锁版策略（R1）按精确版本钉死。

**交付物**（独立仓库 [gaoyue1989/agent-framework-dsh](https://github.com/gaoyue1989/agent-framework-dsh)，pnpm workspace，纯 ESM JS 与 dsh lib 同风格；2026-10-02 自主仓 `agent-framework-dsh/` 目录迁出）：

- `profiles/oaf-web/`：profile（`dsh.profile.bundles=[dsh-base]`）+ `cordis.patch.yml`（env→配置合成、关闭 jsonl 持久化与会话标题 LLM、挂载 webserver + 两个自建插件）
- `@oaf/oaf-mysql-persistence`：`ctx.sessionPersistence` MySQL provider（§6.1 session_log）——storage-contract 官方助手做校验，事务原子性替代 JSONL torn-tail 修复；**provider 必须自装 live 路由**（`session/event` → 写 handle、`session/disposed` → close），这是 jsonl 参照实现 `install(ctx)` 的显式契约
- `@oaf/oaf-server`：`/threads/chat` SSE（帧词表全量映射）、`/subscribe?afterSeq=`、`/status`、`/threads`、`/health`、`/`；事件镜像表（AF session_event 同构）；进程内 turn 租约 + waiting 帧
- `@oaf/oaf-common`：JDBC 解析 / 连接池 / 轻量版本表迁移器（§6.3，每插件独立版本表）

**M0 验收结果**（超出原口径）：`make test`（帧序列化 11 单测，快照钉 AF §9 词表）+ `make smoke`
（自编排 mock LLM 的 24 项 e2e 断言）全绿——含全帧词表序列、delta 拼接 == 真实 LLM 录制件原文
（R3 锚）、MySQL 双层落库、durable SSE 续传 + done、status 五态（M0 覆盖四态）、SIGKILL 冷恢复
（turn 计数/seq/日志三续接）、多用户过滤、M0 声明边界（fileIds 400）。

**部署验证（2026-10-02 补充）**：同一套 24 断言 e2e 以三种运行时驱动全部跑绿——
`spawn`（本地进程）/ `docker`（多阶段镜像容器，`docker rm -f` 强杀冷恢复）/ `k8s`（kind 集群
Deployment+Service+探针，Pod `--force` 强杀由 Deployment 重建 = 真实故障接管路径；镜像
`kind load` 导入、独立 checkpoint 库落 oaf-mysql、Pod 经宿主转发消费 mock LLM）。K8s 形态即
§7/C19 的最小落地：同 env 契约、同探针约定、平台零改动。

**release-agent 切换验证（2026-10-02）**：实现 `oaf-loader` 最小版（§5.4 的 M1 前置）——
AGENTS.md frontmatter → systemPrompt section、mcp-configs → dsh-mcp-client（streamable-http、
fail-soft）、**ask 工具 M0 安全降级 deny**（无确认桥不执行变更；匹配须覆盖 dsh 的
`mcp__{server}__{tool}` 注册名——OAF permissions.tools 写裸名，初版只匹配裸名导致拦截失效并
在真实验证中误发布一个服务，已清理并由 `e2e/ask-deny.test.mjs` 确定性回归钉住）。`oaf-release-agent`
已切换 dsh（svc selector 加 runtime 标签，Java 实例保留回滚）：health/人设/MCP 真实调用/帧词表/
Ingress 全链路实测与 Java 一致；HITL 确认卡等差距清单见独立仓库 README。

**M0 实测澄清（对正文的重要修正）**：

1. **§4.3 补充**：程序化 `ctx.agents.create/resume` 必须显式传 `agentOptions.{provider,model}`——
   `dsh-agent-default-model` 只服务 Host/传输面，不经 registry 路径；OAF 以 patch 配置
   `systemProvider/systemModel`（LLM_* env）注入 oaf-server。
2. **§9 M0→M1 前置确认**：进程内单写者（`SessionAlreadyOwnedError`）与断电恢复（`interruptedTurnClosers`
   合成 closers）已在事件溯源模型下验证成立；HITL 恢复丢参缺陷（e2e §11.3 ①）的 dsh 侧对照验证列入 M2。
3. **事件镜像存储**：M0 以 MySQL 承载 AF `session_event`（M0 口径要求 MySQL 落事件）；迁 Redis
   Streams（C3 目标形态）按计划在 M2，镜像表结构不变、仅换 `EventMirrorStore` 后端。
4. **M2 注意**：`llm-pi-ai` 的 `apiKeyEnv` 经 `ctx.credentials`（dsh-credentials-local 读进程环境）解析，
   Secret 路由后该键必须在进程环境中存在；Keyless 网关需占位凭据（README「已知限制」同款）。


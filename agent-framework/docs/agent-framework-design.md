# Agent Framework — 设计文档

**版本:** v2.1.0 (Java)
**日期:** 2026-08-06

---

## 1. 概述

Agent Framework 是一个基于 **AgentScope Java 2.0 Harness** 的独立可运行 Agent 服务框架，支持 **OAF v0.8.0** 配置规范、**A2A v1.0.0** 通信协议和 **A2UI v0.8** 声明式 UI 扩展。

### 核心特性

| 特性 | 说明 |
|------|------|
| OAF 配置 | 通过 `AGENTS.md` + `skills/` + `mcp-configs/` 目录定义 Agent |
| A2A 协议 | JSON-RPC 2.0 (message/send, message/stream, tasks/*) |
| 工作区 | AgentScope Workspace 目录布局 (AGENTS.md, skills/, subagents/, knowledge/) |
| 记忆管理 | MEMORY.md + memory/ 两层记忆，自动 flush/consolidation |
| 上下文压缩 | 对话摘要、工具结果卸载、溢出兜底 |
| Plan Mode | 只读规划态 + plans/PLAN.md 持久化 |
| 技能自学习 | agent 自动沉淀成功模式为 SKILL.md |
| Channel | agent.channel() + Gateway + SSE |
| 多租户 | IsolationScope.USER 按 userId 自动隔离 |
| 状态持久化 | MysqlDistributedStore (agent_state + agent_fs) |
| **沙箱执行** | **OpenSandbox 集成（可选，SANDBOX_ENABLED=true）：文件操作/Shell 在隔离沙箱执行，USER 级复用，记忆每次请求回写 KV** |

---

## 2. 架构设计

### 2.1 总体架构

```
┌──────────────────────────────────────────────────────────────────┐
│                      Agent Framework v2.1                         │
│                                                                  │
│  ┌─────────────┐   ┌──────────────────┐   ┌──────────────────┐  │
│  │  OAF Loader  │   │  HarnessAgent    │   │  A2A Server      │  │
│  │  AGENTS.md   │──▶│  + Workspace     │──▶│  (AgentScope)    │  │
│  │  + skills/   │   │  + Memory        │   │  + SSE Streaming │  │
│  │  + mcp/      │   │  + Compaction    │   │  + Agent Card    │  │
│  │  + agents/   │   │  + Plan Mode     │   └──────────────────┘  │
│  └─────────────┘   │  + Skills        │                          │
│                    │  + Channel       │   ┌──────────────────┐  │
│                    └──────────────────┘   │  ChatUiChannel   │  │
│                            │              │  + Gateway       │  │
│                            │              └──────────────────┘  │
│                            │              ┌──────────────────┐  │
│                            │              │SessionStream-    │  │
│                            │              │Controller        │  │
│                            │              │POST /threads/    │  │
│                            │              │ {sid}/chat (SSE)  │  │
│                            │              └──────────────────┘  │
│                            │              ┌──────────────────┐  │
│                            │              │ ThreadController  │  │
│                            │              │ GET /threads      │  │
│                            │              └──────────────────┘  │
│                            │              ┌──────────────────┐  │
│                            │              │ConfirmController │  │
│                            ▼              └──────────────────┘  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  MysqlDistributedStore (GreatSQL 3307)                   │    │
│  │  ├── agent_state 表 · AgentState 持久化                  │    │
│  │  ├── agent_fs 表 · 工作区文件 KV 存储                     │    │
│  │  ├── confirm_context 表 · HITL 确认上下文（跨副本共享）    │    │
│  │  ├── turn_lease 表 · Turn 租约（执行权互斥）               │    │
│  │  └── tool_audit_log 表 · 工具调用轻量审计                  │    │
│  │      ├── MEMORY.md · 长期记忆                             │    │
│  │      ├── memory/ · 每日流水账                             │    │
│  │      ├── skills/ · 技能文件                               │    │
│  │      ├── subagents/ · 子 Agent 声明                       │    │
│  │      └── agents/*/sessions/ · 会话日志                    │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
 │  ┌─────────────────────────────────────────────────────────┐    │
 │  │  Filesystem（条件装配，SANDBOX_ENABLED 决定）            │    │
 │  │  ├── 沙箱模式: OpenSandboxFilesystemSpec (SandboxBacked) │    │
 │  │  │   OpenSandbox Server → 沙箱容器 (/workspace)          │    │
 │  │  │   + WorkspaceSyncService 每次请求后回写 KV            │    │
 │  │  └── 默认模式: RemoteFilesystemSpec(IsolationScope.USER) │    │
 │  │      自动按 userId 分桶 · 多租户隔离                     │    │
 │  └─────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────┘
```

### 2.2 模块分层

```
src/main/java/io/agentmanager/framework/
├── AgentFrameworkApplication.java   # Spring Boot 入口
├── config/
│   ├── AgentManagerProperties.java  # 环境变量 @ConfigurationProperties
│   ├── SandboxConfig.java           # 沙箱配置 (SANDBOX_*/OPENSANDBOX_*)
│   ├── OafConfigLoader.java         # AGENTS.md 解析 (SnakeYAML)
│   ├── AgentScopeConfig.java        # Bean 装配 (HarnessAgent, MysqlDistributedStore, 条件装配沙箱)
│   ├── A2AServerConfig.java         # A2A Server Bean (AgentScopeA2aServer + HarnessAgentRunner)
│   └── ChannelConfig.java           # ChatUiChannel Bean
├── model/
│   └── OafConfig.java               # OAF 配置模型 (Java Record, 含 deniedTools)
├── sandbox/opensandbox/
│   ├── OpenSandboxFilesystemSpec.java   # 沙箱文件系统配置 (HarnessAgent.filesystem)
│   ├── OpenSandboxClient.java           # SandboxClient 实现 (create/resume/delete/序列化)
│   ├── OpenSandboxClientOptions.java    # 沙箱配置 (镜像/资源/超时)
│   ├── OpenSandbox.java                 # AbstractBaseSandbox 实现 (exec/快照/延迟注入/stop 回写)
│   ├── OpenSandboxState.java            # 沙箱状态 (sandboxId, Jackson 序列化)
│   ├── WorkspaceSyncService.java        # 沙箱 → KV 回写 (read→edit 语义)
│   ├── SandboxUserKeyMiddleware.java    # 请求级 userId 注入 (onAgent → ThreadLocal)
│   └── SandboxAwareMysqlAgentStateStore.java  # 放宽 sessionId 校验 (沙箱 slot ID 含 "/")
├── service/
│   ├── AgentRuntimeService.java     # Agent 运行时 (invoke/invokeStream + userId)
│   ├── WorkspaceInitializer.java    # OAF → Workspace 转换
│   ├── WorkspaceReader.java         # KV 运行时文件读写 (MEMORY.md/memory/ + 沙箱注入)
│   ├── McpToolRegistrar.java        # MCP 原生注册 (config.yaml → McpClientBuilder, 含 UI 元数据)
│   ├── McpManager.java              # MCP 配置加载
│   ├── UiContextStore.java          # MCP Apps: ui_context 持久化 (4.7 静默更新模型上下文)
│   ├── UiContextInjectionHook.java  # MCP Apps: PreCallEvent 阶段 appendSystemContent 注入 UI 上下文
│   ├── McpResourceProxy.java        # MCP Apps: 资源拉取与工具调用代理 (ui:// HtmlResource + CSP)
│   ├── HarnessAgentRunner.java      # A2A Server 适配器
│   ├── MySqlTaskStore.java          # A2A TaskStore 实现 (读 agent_state, save no-op)
│   ├── StateDataParser.java         # state_data JSON 公共解析 (context[] → 消息 + tool_calls)
│   ├── SessionManager.java          # 会话管理
│   ├── SessionCleanupService.java   # 会话清理 (联动清理 confirm_context/tool_audit_log/turn_lease)
│   ├── TurnLeaseStore.java          # turn_lease 表 acquire/renew/release (执行权互斥)
│   ├── ConfirmContextStore.java     # confirm_context 表 CRUD (跨副本共享 HITL 确认上下文)
│   ├── ToolAuditStore.java          # tool_audit_log 异步批量写 (工具调用轻量审计)
│   ├── LlmLoggingMiddleware.java    # LLM 调用记录中间件 (ModelCallEndEvent → LLMLogger)
│   ├── LLMLogger.java               # LLM 调用日志 (内存存储, /threads/{id}/llm-calls)
│   ├── LogCollector.java            # 日志收集 (内存 Appender)
│   ├── InMemoryLogAppender.java     # 内存日志 Appender
│   └── A2uiService.java             # A2UI JSONL 生成
├── tool/
│   └── BusinessTools.java           # @Tool 自定义工具 (get_current_time, echo)
└── controller/
    ├── InfoController.java          # GET /、/system-prompt
    ├── HealthController.java        # GET /health
    ├── ToolController.java          # GET /skills、/mcp、/tools
    ├── DebugController.java         # GET /debug (页面)
    ├── DebugApiController.java      # GET /debug/* (页面数据 API, 含 /debug/sandbox)
    ├── AgentCardController.java     # GET /.well-known/agent-card.json
    ├── StreamController.java        # GET /chat/stream (Channel SSE, 旧一次性流)
    ├── SessionStreamController.java # POST /threads/{sid}/chat (SSE 单次流, 单次流模式)
    ├── ConfirmController.java       # POST /threads/{sid}/confirm, /confirm-stream (HITL 确认)
    ├── McpProxyController.java      # MCP Apps: /mcp/{server}/resources/ui、/tools/{tool} 代理
    ├── UiContextController.java     # MCP Apps: POST /mcp/ui-context (4.7)
    ├── ThreadController.java        # GET /threads
    └── A2AController.java           # POST / (A2A JSON-RPC 全量透传)
```

### 2.3 请求处理流程

```
Client Request (POST /)
  │
  ▼
A2A Controller (全量透传 SDK，参考官方 A2aJsonRpcController)
  │
  ▼ AgentScopeA2aServer (SDK JsonRpcTransportWrapper)
  │ 解析 JSON-RPC method，统一分发
  ├── message/send ──▶ HarnessAgentRunner.stream() → HarnessAgent
  │     └── 返回标准 A2A Message (kind=message, blocking=true 同步)
  │
  ├── message/stream ──▶ HarnessAgentRunner.stream() → SSE
  │     └── SSE: task → status-update(working) → artifact-update×N → status-update(completed, final) → message
  │
  ├── tasks/get ──▶ MySqlTaskStore.get() → 读 agent_state 构造 Task
  ├── tasks/cancel ──▶ SDK 处理
  └── tasks/resubscribe ──▶ SDK 处理

Client Request (GET /chat/stream?message=...&userId=...)
  │
  ▼
StreamController → ChatUiChannel.sendStream()
  │
  ├── SendOptions.userId(userId) → 自动创建/恢复 session
  ├── HarnessAgent.streamEvents() → LLM API
  └── SSE: TextBlockDeltaEvent / ToolCallStartEvent / ...

Client Request (POST /threads/{sid}/chat, body: {message, userId})
  │
  ▼
SessionStreamController → TurnLeaseStore.acquire() → 等待式获取执行权
  │  等待期间 SSE 每 15s 发 {type:"waiting"} 帧
  │  超时(120s) → 409 turn_in_progress
  │
  ├── 启动续租任务 (20s 间隔, TTL 60s)
  ├── ChatUiChannel.sendStream() → 事件直吐 (SSE 单次流)
  │   ├── 工具类事件 → ToolAuditStore 异步批量写
  │   └── permission_ask → ConfirmContextStore 落库 + release 锁
  ├── AGENT_END / error → 关闭 SSE 流 + release 锁 + 停续租
  └── 完成

Client Request (POST /threads/{sid}/confirm-stream, body: {confirmed, toolCallIds})
  │
  ▼
ConfirmController → ConfirmContextStore.consume() → CAS 防重复
  │  miss → 404 / consumed → 409
  │
  ├── TurnLeaseStore.acquire() → 恢复执行需重新获取执行权
  ├── 反序列化 tool_calls → resumeMsg
  ├── HarnessAgent.streamEvents(resumeMsg, ctx) → SSE 单次流
  └── AGENT_END → release 锁 + 关闭流
```

### 2.4 记忆管理流程

```
call(msg, RuntimeContext(userId, sessionId))
  │
  ▼
WorkspaceContextMiddleware 拼装 system prompt
  │ ├── 读 AGENTS.md (两层读: 先 MySQL, 后本地模板)
  │ ├── 读 MEMORY.md (受 maxContextTokens 预算约束)
  │ └── 读 skills/ → DynamicSkillMiddleware → <available_skills>
  │
  ▼
推理循环 (HarnessAgent)
  │ ├── LLM 思考 → 工具调用 → 结果
  │ ├── 超大工具结果 → ToolResultEvictionMiddleware → 落盘 + 占位符
  │ └── 消息超长 → CompactionMiddleware → 结构化摘要
  │
  ▼
call() 结束
  │ ├── MemoryFlushMiddleware → 提取事实 → memory/YYYY-MM-DD.md
  │ ├── AgentState 序列化 → agent_state 表
  │ └── 工作区文件 → agent_fs 表
  │
  ▼
后台任务 (节流 30 分钟)
  └── MemoryConsolidator → 合并 memory/ → MEMORY.md
```

---

## 3. 核心设计决策

### 3.1 为什么从 ReActAgent 升级到 HarnessAgent？

| 维度 | ReActAgent (v2.0) | HarnessAgent (v2.1) |
|------|-------------------|---------------------|
| 记忆管理 | ❌ | ✅ MEMORY.md + memory/ |
| 上下文压缩 | ❌ | ✅ CompactionConfig |
| Plan Mode | ❌ | ✅ enablePlanMode() |
| 技能自学习 | ❌ | ✅ enableSkillManageTool() |
| 工作区 | ❌ | ✅ Workspace 目录布局 |
| 子 Agent | ❌ | ✅ subagents/*.md |
| 多租户 | 手动 sessionId 前缀 | ✅ IsolationScope.USER |
| Channel | ❌ | ✅ agent.channel() |

### 3.2 为什么从 MysqlAgentStateStore 升级到 MysqlDistributedStore？

| 维度 | MysqlAgentStateStore | MysqlDistributedStore |
|------|---------------------|----------------------|
| AgentState 持久化 | ✅ | ✅ |
| 工作区文件存储 | ❌ | ✅ (JdbcStore) |
| 与 HarnessAgent 兼容 | 需手动配置 | ✅ 一键配置 |
| 与 RemoteFilesystemSpec 兼容 | ❌ | ✅ |
| 自动建表 | agent_state | agent_state + agent_fs |

### 3.3 A2A 协议实现

使用 AgentScope 内置 `AgentScopeA2aServer` 扩展，支持完整的 A2A v1.0.0 方法：
- `message/send`, `message/stream`
- `tasks/get`, `tasks/cancel`, `tasks/resubscribe`
- `MySqlTaskStore` 注入 SDK：tasks/get 从 agent_state 表构造 Task（消息历史由 AgentScope 自动持久化）
- A2AController 全量透传 SDK（官方 `A2aJsonRpcController` 实现），仅保留 message/send + message/stream 的兼容转换（kind/messageId/parts kind）
- Agent Card 自动提供

### 3.4 Part 格式

A2A 协议中 Part 的多态类型鉴别器是 `kind`（不是 `type`）：

```json
// ✅ 正确格式
{"kind": "text", "text": "hello"}
// ❌ 错误格式
{"type": "text", "text": "hello"}
```

### 3.5 流式传输实现

通过 AgentScope Channel 实现：

```java
// ChatUiChannel
chatChannel.sendStream(SendOptions.userId("alice"), "hello")
    .doOnNext(event -> {
        if (event instanceof TextBlockDeltaEvent delta) {
            System.out.print(delta.getDelta());
        }
    })
```

---

## 4. 多租户隔离

### 4.1 IsolationScope

| Scope | 命名空间键 | 典型场景 |
|---|---|---|
| `USER`（默认） | `agents/<agentId>/users/<userId>/...` | 同一用户跨会话共享记忆 |
| `SESSION` | `agents/<agentId>/sessions/<sessionId>/...` | 每个会话完全隔离 |
| `AGENT` | `agents/<agentId>/shared/...` | 共享知识库型 agent |

### 4.2 隔离矩阵

| 数据类型 | 隔离维度 | 存储位置 |
|---|---|---|
| AgentState | `(userId, sessionId)` | `agent_state` 表 |
| MEMORY.md | `userId` | `agent_fs` 表 |
| memory/ | `userId` | `agent_fs` 表 |
| skills/ | 共享 + 用户覆盖 | `agent_fs` 表 |
| sessions/ | `userId` | `agent_fs` 表 |
| 沙箱实例（沙箱模式） | `userId`（USER scope） | OpenSandbox Server；状态存 `agent_state`（slot `sandbox/user/{agentId}/{userId}`） |

---

## 4.5 沙箱模式（OpenSandbox）

### 4.5.1 概述

`SANDBOX_ENABLED=true` 时，filesystem 从 `RemoteFilesystemSpec` 切换为 `OpenSandboxFilesystemSpec`（`SandboxBackedFilesystem`）：agent 的文件操作与 Shell 命令在 OpenSandbox 沙箱容器内执行。完整设计见 [opensandbox-integration-plan.md](opensandbox-integration-plan.md)。

### 4.5.2 关键机制

| 机制 | 说明 |
|------|------|
| 沙箱创建/复用 | `OpenSandboxClient.create()` 创建；`connector().connect()` 恢复（**不能用 `resumer().resume()`**，Running 沙箱会 409）；resume 404 → 框架自动降级新建 |
| USER 级复用 | `IsolationScope.USER`，同 userId 跨会话复用；userId 缺失时降级 SESSION |
| 静态模板注入 | 框架 workspace projection（tar 流 hydrateWorkspace）：AGENTS.md/skills/subagents/knowledge |
| KV 运行时文件注入 | 首次 exec 延迟注入（`SandboxClient.create()` 无 RuntimeContext 拿不到 userId） |
| 记忆回写 | `OpenSandbox.stop()`（框架每次 call 结束调用）→ `WorkspaceSyncService` 拉取沙箱 MEMORY.md/memory/ → KV（read→edit 语义） |
| userId 传递 | `SandboxUserKeyMiddleware.onAgent` 注入（框架内部 exec 的 RuntimeContext 为空，实测）→ ThreadLocal → 沙箱绑定 |
| 并发控制 | 框架自动注入 `JdbcSandboxExecutionGuard`（MySQL GET_LOCK） |
| 状态持久化 | `SandboxAwareMysqlAgentStateStore`（官方 MysqlAgentStateStore 拒绝含 "/" 的 slot ID） |

### 4.5.3 沙箱模式数据流

```
请求 (userId=alice)
  ├─ SandboxUserKeyMiddleware.onAgent → 注入 userId
  ├─ SandboxManager.acquire → resume(connector) / create(降级)
  │     ├─ 框架投影注入静态模板 (AGENTS.md 等)
  │     └─ 首次 exec 时注入 KV 记忆 (MEMORY.md/memory/)
  ├─ agent 执行：文件操作/Shell → 沙箱容器
  │     └─ 记忆读写 → 沙箱内 /workspace/MEMORY.md
  └─ 请求结束 stop() → WorkspaceSyncService 回写 KV (agent_fs)
        ├─ MEMORY.md → namespace=[userId]
        └─ memory/*.md → namespace=[userId]
```

### 4.5.4 已知限制（SDK 2.0.0）

- **A2A 流不携带工具参数**：`agentscope-extensions-a2a-server` 输出的 `tool_use` data part 只有工具名 + call_id（`getInput()` 为空）；debug 页面从 agent_state（history API）按 tool_call_id 匹配补全参数
- `RemoteFilesystem.write` 对已存在文件返回失败（防覆盖保护），回写需 read→edit 或 BaseStore 直写（已用 read→edit）

---

## 5. 配置规范

### 5.1 OAF 目录结构

```
config/
├── AGENTS.md                  # 主配置 (YAML frontmatter + Markdown)
├── skills/                    # 可选：本地技能
│   └── <skill-name>/
│       ├── SKILL.md           # 技能清单
│       └── scripts/
│           └── tool.py        # Python 实现
└── mcp-configs/               # 可选：MCP 服务器
    └── <server-name>/
        ├── ActiveMCP.json     # 工具选择
        └── config.yaml        # 连接配置
```

### 5.2 Workspace 目录布局（自动生成）

```
.agentscope/workspace/
├── AGENTS.md                    ← OAF frontmatter + body 转换
├── tools.json                   ← OAF mcpServers 转换
├── skills/                      ← OAF skills (本地复制)
├── subagents/                   ← OAF agents 转换
├── knowledge/                   ← 知识库
├── MEMORY.md                    ← 长期记忆 (自动生成)
├── memory/                      ← 每日流水账 (自动生成)
│   └── YYYY-MM-DD.md
└── plans/                       ← Plan Mode 计划 (自动生成)
    └── PLAN.md
```

### 5.3 环境变量

| 变量 | 默认值 | 必填 | 说明 |
|------|--------|------|------|
| `LLM_API_KEY` | — | ✓ | LLM API 密钥 |
| `LLM_MODEL_ID` | — | ✓ | 模型 ID |
| `LLM_BASE_URL` | — | ✓ | LLM API 端点 URL |
| `LLM_PROVIDER` | `openai` | | 提供商标识 |
| `LLM_TEMPERATURE` | `0.7` | | 生成温度 |
| `LLM_MAX_TOKENS` | `4096` | | 最大输出 token |
| `LLM_TIMEOUT` | `120` | | API 调用超时(秒) |
| `AGENT_CONFIG_DIR` | `/config` | | OAF 配置目录 |
| `SERVER_HOST` | `0.0.0.0` | | 监听地址 |
| `SERVER_PORT` | `8100` | | 服务端口 |
| `CHECKPOINT_JDBC_URL` | `jdbc:mysql://127.0.0.1:3307/agent_manager_test` | | MySQL JDBC URL |
| `CHECKPOINT_USERNAME` | `agent_manager` | | MySQL 用户名 |
| `CHECKPOINT_PASSWORD` | `Agent@Manager2026` | | MySQL 密码 |
| `SANDBOX_ENABLED` | `false` | | 沙箱模式开关（true 时 filesystem 切换为 OpenSandbox） |
| `SANDBOX_IMAGE` | `opensandbox/code-interpreter:v1.1.0` | | 沙箱镜像 |
| `SANDBOX_TIMEOUT_MINUTES` | `60` | | 沙箱超时（到期自动销毁，resume 404 自动降级重建） |
| `SANDBOX_MEMORY_MB` | `1024` | | 沙箱内存限制 |
| `SANDBOX_CPU_COUNT` | `1` | | 沙箱 CPU 限制 |
| `OPENSANDBOX_SERVER_URL` | `192.168.31.155:8090` | | OpenSandbox Server 地址 |
| `OPENSANDBOX_API_KEY` | — | ✓(沙箱模式) | OpenSandbox API 密钥 |

---

## 6. 依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| agentscope-harness | 2.0.0 | HarnessAgent + Workspace + Memory + Compaction + Filesystem |
| agentscope-extensions-model-openai | 2.0.0 | OpenAI 兼容 LLM |
| agentscope-extensions-mysql | 2.0.0 | MysqlDistributedStore (agent_state + agent_fs) |
| agentscope-extensions-a2a-server | 2.0.0 | A2A 协议 Server |
| com.alibaba.opensandbox:sandbox | 1.0.18 | OpenSandbox Java SDK（沙箱模式） |
| Spring Boot | 3.3.5 | HTTP 服务框架 |
| SnakeYAML | 2.x | YAML frontmatter 解析 |
| MySQL Connector/J | 8.x | MySQL JDBC 驱动 |
| HikariCP | 5.x | 连接池 |

---

## 7. MCP 集成

### 7.1 注册方式

通过 `McpToolRegistrar` 从 `mcp-configs/{server}/config.yaml` 读取连接配置，使用 AgentScope 原生 `McpClientBuilder` 构建 MCP 客户端并注册到 Toolkit。

支持三种传输：`sse` / `streamableHttp` / `stdio`。

**config.yaml 格式**:
```yaml
server: weather-service
vendor: weather
version: "1.0.0"
connection:
  type: streamableHttp   # sse / streamableHttp / stdio
  url: http://127.0.0.1:8811/mcp
  timeout: 60
auth:
  type: bearer
  token: ${MCP_TOKEN}    # 支持环境变量
permissions:
  read_only: true         # 强制工具只读，绕过 HITL 授权
```

**MCP Apps UI 元数据（阶段一/二）**：同一 config.yaml 可声明工具 UI 资源与展示范围：

```yaml
ui:
  app_only: true                # 可选：该 server 下声明 ui 的工具仅作卡片展示、不入 LLM 工具集
  tools:
    get_time:
      resource_uri: ui://get-time/mcp-app.html   # 卡片渲染源（经 /mcp/{server}/resources/ui 拉取）
```

- `resource_uri` 静态声明优先；无声明时回退工具 `meta()` 的 `_meta` 动态发现（渲染前预检）
- TOOL_CALL_START 事件经 `resolveUiRef` 解析，SSE payload 携带 `ui.resourceUri/ui.server` → 前端渲染卡片
- `app_only` 工具不注册到 Toolkit（LLM 不可见），仅 `/tools` 接口展示（供 Debug 页预检）

### 7.2 permissions.read_only 权限控制

AgentScope 的 `McpTool.checkPermissions()` 对非只读 MCP 工具返回 `PermissionDecision.ask()`（需 HITL 授权），导致工具调用挂起。三种方式让 MCP 工具只读放行：

| 方式 | 来源 | 说明 |
|------|------|------|
| server annotations | `ToolAnnotations(readOnlyHint=True)` | MCP 协议标准，server 端标注 |
| **config.yaml 配置** | `permissions.read_only: true` | **本框架支持**，无需改 server |
| AgentScope 全局 bypass | `PermissionMode.BYPASS` | 不推荐，安全风险高 |

**优先级**: config.yaml `permissions.read_only` > server `annotations.readOnlyHint` > 默认 HITL ask

**实现**: `McpToolRegistrar.isReadOnlyConfigured()` 读取 `permissions.read_only`，为 `true` 时走 `registerReadOnly()` 路径，手动构造 `readOnly=true` 的 McpTool 注册。

### 7.3 ActiveMCP 工具子集

通过 `mcp-configs/{server}/ActiveMCP.json` 声明工具子集，`enabled: false` 的工具**不注册**到 Toolkit（LLM 不可见、不可调用）：

```json
{
  "selectedTools": [
    {"name": "get_user_info", "enabled": true},
    {"name": "query_db", "enabled": true},
    {"name": "transfer_money", "enabled": false}
  ]
}
```

**行为细节：**
- 存在 ActiveMCP.json 时，该 server 走手动注册路径（`registerReadOnly`），未声明的工具也注册（仅 `enabled: false` 被排除）
- 不存在 ActiveMCP.json 时走标准注册，工具全量注册
- 实现：`McpToolRegistrar.loadActiveMcpConfig()`

### 7.4 工具命名策略

MCP 工具命名涉及两个场景：

| 场景 | 命名 | 说明 |
|------|------|------|
| **Toolkit 注册**（LLM 调用） | 远端裸名 `get_weather` | `McpTool.getName()` 是 `final` 字段，`callAsync` 用它转发给远端；必须用裸名 |
| **API 展示**（`/tools`、`/mcp`） | `mcp__{server}__{tool}` | 通过 `ToolInfo.displayName` 展示，避免跨 server 同名混淆 |

**限制**：`McpTool.getName()` 是 `private final`，`callAsync` 用 `this.getName()` 转发给 `clientWrapper.callTool()`，无法分离 LLM 暴露名和执行名。跨 server 同名工具（如两个 server 都有 `get_info`）在 Toolkit 中会冲突，通过 `serverName` 字段区分。

### 7.5 MCP 工具执行链路

```
LLM 推理 → 选择工具 (如 mcp__finance__get_user_info)
  → McpTool.callAsync → McpSyncClientWrapper.callTool("get_user_info", args)
  → streamable-http POST → MCP Server
  → 返回 JSON 天气数据 → ToolResultBlock
  → LLM 组织最终回答 → 返回用户
```

### 7.6 注册流程

```
McpToolRegistrar.registerAll()
  ├── loadActiveMcpConfig(mcp) → 读取 ActiveMCP.json（无则 null）
  ├── isReadOnlyConfigured(mcp) → 读取 config.yaml permissions.read_only
  ├── [true 或 ActiveMCP 存在] → registerReadOnly()
  │     ├── 过滤 enabled=false 的工具
  │     └── 手动构造 readOnly=true 的 McpTool（命名 mcp__{server}__{tool}）
  └── [false 且无 ActiveMCP] → toolkit.registerMcpClient(wrapper).block() → 标准注册
```

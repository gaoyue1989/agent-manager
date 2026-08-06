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
│                            ▼              └──────────────────┘  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  MysqlDistributedStore (GreatSQL 3307)                   │    │
│  │  ├── agent_state 表 · AgentState 持久化                  │    │
│  │  └── agent_fs 表 · 工作区文件 KV 存储                     │    │
│  │      ├── MEMORY.md · 长期记忆                             │    │
│  │      ├── memory/ · 每日流水账                             │    │
│  │      ├── skills/ · 技能文件                               │    │
│  │      ├── subagents/ · 子 Agent 声明                       │    │
│  │      └── agents/*/sessions/ · 会话日志                    │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  RemoteFilesystemSpec(IsolationScope.USER)               │    │
│  │  自动按 userId 分桶 · 多租户隔离                          │    │
│  └─────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────┘
```

### 2.2 模块分层

```
src/main/java/io/agentmanager/framework/
├── AgentFrameworkApplication.java   # Spring Boot 入口
├── config/
│   ├── AgentManagerProperties.java  # 环境变量 @ConfigurationProperties
│   ├── OafConfigLoader.java         # AGENTS.md 解析 (SnakeYAML)
│   ├── AgentScopeConfig.java        # Bean 装配 (HarnessAgent, MysqlDistributedStore)
│   ├── A2AServerConfig.java         # A2A Server Bean (AgentScopeA2aServer + HarnessAgentRunner)
│   └── ChannelConfig.java           # ChatUiChannel Bean
├── model/
│   └── OafConfig.java               # OAF 配置模型 (Java Record, 含 deniedTools)
├── service/
│   ├── AgentRuntimeService.java     # Agent 运行时 (invoke/invokeStream + userId)
│   ├── WorkspaceInitializer.java    # OAF → Workspace 转换
│   ├── McpToolRegistrar.java        # MCP 原生注册 (config.yaml → McpClientBuilder)
│   ├── McpManager.java              # MCP 配置加载
│   ├── HarnessAgentRunner.java      # A2A Server 适配器
│   ├── A2uiService.java             # A2UI JSONL 生成
│   └── LLMLogger.java               # LLM 调用日志
├── tool/
│   └── BusinessTools.java           # @Tool 自定义工具 (get_current_time, echo)
└── controller/
    ├── InfoController.java          # GET /、/system-prompt
    ├── HealthController.java        # GET /health
    ├── ToolController.java          # GET /skills、/mcp、/tools
    ├── DebugController.java         # GET /debug
    ├── StreamController.java        # GET /chat/stream (Channel SSE)
    └── ThreadController.java        # GET /threads
```

### 2.3 请求处理流程

```
Client Request (POST /)
  │
  ▼
A2A Server (AgentScopeA2aServer)
  │ 解析 JSON-RPC method
  ├── message/send ──▶ HarnessAgent.call() → LLM API
  │     └── 返回 A2A Task { id, status: { state, message }, artifacts }
  │
  ├── message/stream ──▶ HarnessAgent.streamEvents()
  │     └── SSE: TaskStatusUpdateEvent / TaskArtifactUpdateEvent
  │
  ├── tasks/get ──▶ 查询 Task 状态
  ├── tasks/list ──▶ 列出 Tasks
  └── tasks/cancel ──▶ 取消 Task

Client Request (GET /chat/stream?message=...&userId=...)
  │
  ▼
StreamController → ChatUiChannel.sendStream()
  │
  ├── SendOptions.userId(userId) → 自动创建/恢复 session
  ├── HarnessAgent.streamEvents() → LLM API
  └── SSE: TextBlockDeltaEvent / ToolCallStartEvent / ...
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
- `tasks/get`, `tasks/list`, `tasks/cancel`
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

---

## 6. 依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| agentscope-harness | 2.0.0 | HarnessAgent + Workspace + Memory + Compaction + Filesystem |
| agentscope-extensions-model-openai | 2.0.0 | OpenAI 兼容 LLM |
| agentscope-extensions-mysql | 2.0.0 | MysqlDistributedStore (agent_state + agent_fs) |
| agentscope-extensions-a2a-server | 2.0.0 | A2A 协议 Server |
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

### 7.2 permissions.read_only 权限控制

AgentScope 的 `McpTool.checkPermissions()` 对非只读 MCP 工具返回 `PermissionDecision.ask()`（需 HITL 授权），导致工具调用挂起。三种方式让 MCP 工具只读放行：

| 方式 | 来源 | 说明 |
|------|------|------|
| server annotations | `ToolAnnotations(readOnlyHint=True)` | MCP 协议标准，server 端标注 |
| **config.yaml 配置** | `permissions.read_only: true` | **本框架支持**，无需改 server |
| AgentScope 全局 bypass | `PermissionMode.BYPASS` | 不推荐，安全风险高 |

**优先级**: config.yaml `permissions.read_only` > server `annotations.readOnlyHint` > 默认 HITL ask

**实现**: `McpToolRegistrar.isReadOnlyConfigured()` 读取 `permissions.read_only`，为 `true` 时走 `registerReadOnly()` 路径，手动构造 `readOnly=true` 的 McpTool 注册。

### 7.3 MCP 工具执行链路

```
LLM 推理 → 选择工具 (如 get_weather)
  → McpTool.callAsync → McpSyncClientWrapper.callTool("get_weather", args)
  → streamable-http POST → MCP Server
  → 返回 JSON 天气数据 → ToolResultBlock
  → LLM 组织最终回答 → 返回用户
```

### 7.4 注册流程

```
McpToolRegistrar.registerAll()
  ├── isReadOnlyConfigured(mcp) → 读取 config.yaml permissions.read_only
  ├── [true]  → registerReadOnly() → 遍历 MCP 工具，手动构造 readOnly=true 的 McpTool 注册
  └── [false] → toolkit.registerMcpClient(wrapper).block() → 标准注册（依赖 server annotations）
```

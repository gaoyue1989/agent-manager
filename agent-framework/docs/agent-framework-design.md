# Agent Framework — 设计文档

**版本:** v2.0.0 (Java)
**日期:** 2026-07-13

---

## 1. 概述

Agent Framework 是一个基于 **AgentScope Java 2.0** 的独立可运行 Agent 服务框架，支持 **OAF v0.8.0** 配置规范、**A2A v1.0.0** 通信协议和 **A2UI v0.8** 声明式 UI 扩展。

### 核心特性

| 特性 | 说明 |
|------|------|
| OAF 配置 | 通过 `AGENTS.md` + `skills/` + `mcp-configs/` 目录定义 Agent |
| A2A 协议 | JSON-RPC 2.0 (message/send, message/stream) |
| 多工具 | Bash / Read / Edit / Grep 内置工具 + MCP 扩展 |
| Skills | 动态加载技能模块 (SKILL.md + tool.py) |
| 流式输出 | 逐 token SSE 推送 + 内嵌调试页面 |
| 状态持久化 | MySQL AgentStateStore 实现会话持久化 |
| MCP Apps Host | 支持 tool_call _meta.ui 元数据传递和 iframe 渲染 |

---

## 2. 架构设计

### 2.1 总体架构

```
┌──────────────────────────────────────────────────────────────┐
│                      Agent Framework                          │
│                                                              │
│  ┌─────────────┐   ┌──────────────┐   ┌──────────────────┐  │
│  │  OAF Loader  │   │ AgentScope   │   │  A2A Controller  │  │
│  │  AGENTS.md   │──▶│ ReActAgent   │──▶│  + SSE Streaming │  │
│  │  + skills/   │   │ + Skills     │   │  + Debug Page    │  │
│  │  + mcp/      │   │ + MCP Tools  │   └──────────────────┘  │
│  └─────────────┘   └──────────────┘                          │
│                    │         │                                │
│                    ▼         ▼                                │
│  ┌─────────────────────────────────────────────┐             │
│  │  MySQL AgentStateStore (GreatSQL 3307)       │             │
│  │  agent_state 表 · session 持久化              │             │
│  └─────────────────────────────────────────────┘             │
│                                                              │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │              配置文件挂载 (/config)                       │ │
│  │   /config/AGENTS.md (必需)                               │ │
│  │   /config/skills/  (可选)                                │ │
│  │   /config/mcp-configs/ (可选)                            │ │
│  └─────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

### 2.2 模块分层

```
src/main/java/io/agentmanager/framework/
├── AgentFrameworkApplication.java   # Spring Boot 入口
├── config/
│   ├── AgentManagerProperties.java  # 环境变量 @ConfigurationProperties
│   ├── OafConfigLoader.java         # AGENTS.md 解析 (SnakeYAML)
│   ├── AgentScopeConfig.java        # Bean 装配 (ReActAgent, DataSource, RuntimeService)
│   └── A2AServerConfig.java         # A2A Server Bean (AgentScopeA2aServer)
├── model/
│   └── OafConfig.java               # OAF 配置模型 (Java Record)
├── service/
│   ├── AgentRuntimeService.java     # Agent 运行时 (invoke/invokeStream/buildSystemPrompt)
│   ├── SkillManager.java            # Skill 加载 (SKILL.md + tool.py)
│   ├── McpManager.java              # MCP 配置加载
│   ├── A2uiService.java             # A2UI JSONL 生成
│   └── LLMLogger.java               # LLM 调用日志
└── controller/
    ├── InfoController.java          # GET /、/system-prompt
    ├── HealthController.java        # GET /health
    ├── ToolController.java          # GET /skills、/mcp、/tools
    ├── AgentCardController.java     # GET /.well-known/agent-card.json
    ├── DebugController.java         # GET /debug
    ├── StreamController.java        # POST /chat/stream (SSE)
    ├── ThreadController.java        # GET /threads
    └── A2AController.java           # POST / (A2A JSON-RPC)
```

### 2.3 请求处理流程

```
Client Request (POST /)
  │
  ▼
A2AController.handleA2A()
  │ 解析 JSON-RPC method
  ├── message/send ──▶ handleMessageSend()
  │     └── agentRuntime.invoke() → ReActAgent.call() → LLM API
  │     └── 返回 JSON-RPC 响应 { result: { id, status, result: { message } } }
  │
  └── message/stream ──▶ handleStreaming()
        └── agentRuntime.invokeStream() → ReActAgent.streamEvents()
        └── SSE: data: {"type":"task_update","state":"working"}
              → data: {"type":"token","token":"..."}
              → data: {"type":"tool_call",...}
              → data: {"type":"tool_result",...}
              → data: {"type":"task_update","state":"completed"}
              → data: {"type":"done"}
              → data: [DONE]
```

### 2.4 工具调用流程

```
Agent.invoke("用 bash 执行 uname -a")
  │
  ▼
AgentRuntimeService.invoke()
  │
  ├── RuntimeContext(sessionId, userId) 创建
  ├── UserMessage("user", message) 创建
  ├── agent.call([userMsg], ctx) → ReActAgent 执行
  │     ├── LLM 思考 → 决定调用 bash 工具
  │     ├── AgentScope 调用 ShellTool.run("uname -a")
  │     ├── 结果返回给 LLM
  │     └── LLM 生成最终回答
  └── 返回 { response: "...", thread_id: "..." }
```

---

## 3. 核心设计决策

### 3.1 技术选型: AgentScope Java vs DeepAgents Python

| 对比项 | AgentScope Java | DeepAgents Python |
|--------|-----------------|-------------------|
| 运行时 | JVM 21 (Spring Boot 3.3) | Python 3.11+ (FastAPI) |
| Agent 模型 | ReActAgent | create_deep_agent |
| LLM 适配 | OpenAIChatModel | ChatOpenAI |
| 状态存储 | MysqlAgentStateStore | AsyncMySaver (LangGraph) |
| A2A 协议 | 自定义 Controller | a2a_routes.py |
| 构建工具 | Maven | pip |
| 部署 | JAR (Docker) | Python (Docker) |

### 3.2 A2A Controller (替代 AgentScopeA2aServer)

`agentscope-extensions-a2a-server` 依赖了 `agentscope-core` 中不存在的 `PartParserRouter` 类，因此使用自定义 A2A Controller 替代：

- `message/send` → `AgentRuntimeService.invoke()` → JSON 响应
- `message/stream` → `AgentRuntimeService.invokeStream()` → SSE 流

### 3.3 Part 格式

A2A 协议中 Part 的多态类型鉴别器是 `kind`（不是 `type`）：

```json
// ✅ 正确格式
{"kind": "text", "text": "hello"}
// ❌ 错误格式
{"type": "text", "text": "hello"}
```

控制器兼容两种格式：优先读取 `kind`，为 `null` 时默认视为 `text`。

### 3.4 流式传输实现

采用 AgentScope 的 `streamEvents()` API 实现 token 级流式 SSE：

```java
agent.streamEvents(List.of(userMsg), ctx)
    .doOnNext(event -> {
        if (event instanceof TextBlockDeltaEvent) {
            String delta = ((TextBlockDeltaEvent) event).getDelta();
            sink.next(Map.of("type", "token", "token", delta));
        }
    })
```

SSE 格式：
```
data: {"type":"task_update","state":"working",...}
data: {"type":"token","token":"Hello",...}
data: [DONE]
```

---

## 4. 配置规范

### 4.1 OAF 目录结构

```
config/
├── AGENTS.md                  # 主配置 (YAML frontmatter + Markdown)
├── skills/                    # 可选：本地技能
│   └── <skill-name>/
│       ├── SKILL.md           # 技能清单
│       └── scripts/
│           └── tool.py        # Python 实现 (main() 函数)
└── mcp-configs/               # 可选：MCP 服务器
    └── <server-name>/
        ├── ActiveMCP.json     # 工具选择
        └── config.yaml        # 连接配置
```

### 4.2 AGENTS.md 示例

```yaml
---
name: "My Agent"
vendorKey: "myorg"
agentKey: "my-agent"
version: "1.0.0"
slug: "myorg/my-agent"
description: "A custom agent"

skills:
  - name: "bash-tool"
    source: "local"
    version: "1.0.0"

mcpServers:
  - vendor: "weather"
    server: "weather-service"
    version: "1.0.0"
    configDir: "mcp-configs/weather"

tools:
  - Read
  - Bash
  - Edit
  - Grep
---

# System Prompt

You are a helpful AI assistant.
```

### 4.3 环境变量

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

## 5. 依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| agentscope-harness | 2.0.0 | Agent 框架内核 |
| agentscope-extensions-model-openai | 2.0.0 | OpenAI 兼容 LLM |
| agentscope-extensions-mysql | 2.0.0 | MySQL 状态存储 |
| agentscope-extensions-a2a-server | 2.0.0 | A2A 协议（仅 AgentCard） |
| Spring Boot | 3.3.5 | HTTP 服务框架 |
| SnakeYAML | 2.x | YAML frontmatter 解析 |
| MySQL Connector/J | 8.x | MySQL JDBC 驱动 |
| HikariCP | 5.x | 连接池 |

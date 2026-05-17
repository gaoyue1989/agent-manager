# Agent Framework — 设计文档

**版本:** v1.2.0
**日期:** 2026-05-17

---

## 1. 概述

Agent Framework 是一个基于 **DeepAgents** 的独立可运行 Agent 服务框架，支持 **OAF v0.8.0** 配置规范、**A2A v1.0.0** 通信协议和 **A2UI v0.8** 声明式 UI 扩展，通过挂载配置文件实现 Docker 化部署。

### 核心特性

| 特性 | 说明 |
|------|------|
| OAF 配置 | 通过 `AGENTS.md` + `skills/` + `mcp-configs/` 目录定义 Agent |
| A2A 协议 | JSON-RPC 2.0 + HTTP REST + SSE 流式传输 |
| A2UI 组件 | surfaceUpdate / beginRendering 声明式 UI 流 |
| 多工具 | Bash / Read / Edit / Grep 内置工具 + 自定义工具 + MCP 扩展 |
| Skills | 动态加载 Python 脚本技能模块 |
| **自定义工具** | **在 custom-tools/ 目录下放置 @tool 装饰的 Python 脚本，LLM 可主动调用** |
| 流式输出 | 真实的逐 token SSE 推送 + 内嵌调试页面 |
| **Checkpoint 持久化** | **MySQL 存储 thread_id 会话, 支持多轮对话记忆和跨重启恢复** |
| **MCP Apps Host** | **符合 SEP-1865 规范，支持 tool_call _meta.ui 元数据传递和 iframe 渲染** |

---

## 2. 架构设计

### 2.1 总体架构

```
┌──────────────────────────────────────────────────────────────┐
│                      Agent Framework                          │
│                                                              │
│  ┌─────────────┐   ┌──────────────┐   ┌──────────────────┐  │
│  │  OAF Loader  │   │ DeepAgents   │   │  A2A Server      │  │
│  │  AGENTS.md   │──▶│ Runtime      │──▶│  + A2UI Ext      │  │
│  │  + skills/   │   │ + Skills     │   │  + SSE Streaming │  │
│  │  + mcp/      │   │ + MCP Tools  │   │  + Debug Page    │  │
│  └─────────────┘   └──────────────┘   └──────────────────┘  │
│                                                              │
│  ┌─────────────────┐                                         │
│  │  Checkpoint Mgr  │  MySQL (GreatSQL 3307)                │
│  │  AsyncMySaver    │  checkpoints / blobs / writes          │
│  │  thread_id 持久化 │  thread CRUD (REST + JSON-RPC)        │
│  └─────────────────┘                                         │
│                                                              │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │              配置文件挂载 (/config)                       │ │
│  │   /config/AGENTS.md (必需)                               │ │
│  │   /config/skills/  (可选)                                │ │
│  │   /config/custom-tools/ (可选)                           │ │
│  │   /config/mcp-configs/ (可选)                            │ │
│  └─────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

### 2.2 模块分层

```
server/
├── config.py                   # 配置加载层
│   └── AppConfig / ServerConfig / LLMConfig (Pydantic + env)
├── models/                     # 数据模型层
│   ├── oaf_types.py            # OAF v0.8.0 类型 (SkillConfig, MCPServerConfig, ...)
│   └── a2a_types.py            # A2A 协议类型 (Task, Message, Part, Artifact)
├── services/                   # 业务服务层
│   ├── oaf_loader.py           # OAF 配置解析 (AGENTS.md frontmatter + body)
│   ├── agent_runtime.py        # DeepAgents 运行时封装 (invoke/stream/tools)
│   ├── skill_manager.py        # Skill 动态加载器 (importlib + SKILL.md)
│   ├── mcp_manager.py          # MCP 连接管理 (ActiveMCP.json + config.yaml)
│   ├── checkpoint_manager.py   # MySQL checkpoint 管理 (thread_id 持久化)
│   ├── custom_tool_manager.py  # 自定义工具动态加载 (importlib + @tool)
│   ├── a2ui_service.py         # A2UI JSONL 生成 (surfaceUpdate/beginRendering)
│   └── chat_model.py           # ChatOpenAIReasoning (GLM-5 reasoning_content 兼容)
├── routes/                     # 路由层
│   ├── a2a_routes.py           # A2A 端点 (JSON-RPC + REST + SSE + thread 方法)
│   ├── thread_routes.py        # Thread 管理 (GET/DELETE /threads, /threads/{id})
│   ├── mcp_routes.py           # MCP 资源代理 (resources/read, tools/list)
│   ├── agent_card.py           # Agent Card 发现
│   └── debug_ui.py             # 内嵌调试页面
├── templates/
│   └── debug_page.html         # 前端调试页面 (单文件, SSE + A2UI + MCP Apps Host)
├── wsgi.py                     # gunicorn 入口 (调用 create_app)
├── main.py                     # uvicorn 启动入口
└── app.py                      # 应用工厂 (create_app)
```

### 2.3 请求处理流程

```
Client Request (POST /)
  │
  ▼
A2ARoutes.jsonrpc_handler()
  │ 解析 JSON-RPC method
  ├── message/send  ──▶ _handle_send_message()
  │     └── agent.invoke() → DeepAgents agent.invoke() → LLM API
  │     └── 返回 Task { id, status, artifacts }
  │
  ├── message/stream ──▶ StreamingResponse(_handle_stream_message)
  │     └── agent.invoke_stream() → agent.astream(stream_mode="messages")
  │     └── SSE: event: task_update → event: token →
  │           event: tool_call (含 _meta.ui 元数据) →
  │           event: tool_result → event: done
  │
  ├── tasks/get ──▶ 从 tasks_store 返回 Task
  └── tasks/list ──▶ 返回 tasks_store 全部 Task

MCP Apps Host 流程 (调试页面):
  │
  ├── SSE tool_call 事件携带 _meta.ui.resourceUri
  ├── 前端注册到 mcpAppsRegistry {tcId → {uri, name, args}}
  ├── tool_result 到达 → fetchMCPResource(server, uri)
  │     └── POST /mcp/resources/read → MCPRoutes
  │           ├── 优先使用活动 SSE session (MultiServerMCPClient)
  │           └── 降级到直接 HTTP POST
  └── 资源 HTML 渲染到 sandbox iframe → postMessage 传递 tool_args
```

### 2.4 工具调用流程

```
Agent.invoke("用bash执行 uname -a")
  │
  ▼
DeepAgents agent.invoke(messages)
  │
  ├── LLM 思考 → 决定调用 bash_execute 工具
  ├── agent 调用 bash_execute(command="uname -a")
  ├── subprocess.run("uname -a", shell=True, ...)
  ├── 结果返回给 LLM
  └── LLM 生成最终回答
```

---

## 3. 核心设计决策

### 3.1 ChatOpenAIReasoning — GLM-5 兼容

GLM-5 模型将流式 token 放在 `reasoning_content` 字段，标准 ChatOpenAI 只读取 `content`。

**解决**: 子类化 `ChatOpenAI`，重写 `_convert_chunk_to_generation_chunk`，将 `reasoning_content` 合并到 `content`：

```python
class ChatOpenAIReasoning(ChatOpenAI):
    def _convert_chunk_to_generation_chunk(self, chunk, ...):
        delta = chunk["choices"][0].get("delta", {})
        if delta.get("reasoning_content") and not delta.get("content"):
            delta = dict(delta)
            delta["content"] = delta["reasoning_content"]
            chunk["choices"][0]["delta"] = delta
        return super()._convert_chunk_to_generation_chunk(chunk, ...)
```

### 3.2 流式传输实现

采用 LangGraph `stream_mode="messages"` 实现 token 级流式：

```python
async for msg, metadata in agent.astream(
    {"messages": [...]},
    stream_mode="messages",
):
    chunk = msg if not isinstance(msg, tuple) else msg[0]
    content = getattr(chunk, "content", "")
    yield content  # 逐 token SSE 推送
```

### 3.3 双路径回退

| 方法 | 主路径 | 回退路径 |
|------|--------|---------|
| `invoke()` | DeepAgents `agent.invoke()` | `httpx.post()` 直连 LLM API |
| `invoke_stream()` | DeepAgents `agent.astream(stream_mode="messages")` | `httpx.stream()` 直连 LLM SSE |

回退路径确保在 DeepAgents 不可用或异常时仍然能返回响应。

### 3.4 工具定义

根据 OAF 配置中的 `tools` 字段动态创建 LangChain 工具：

| tools 值 | 对应工具 | 实现 |
|----------|---------|------|
| `bash` / `execute` | `bash_execute` | `subprocess.run(shell=True)` |
| `read` | `read_file` | `Path.read_text()` |
| `edit` | `edit_file` | 字符串替换 → `Path.write_text()` |
| `grep` | `grep_search` | `subprocess.run("grep -rn")` |

### 3.5 自定义工具

在 `custom-tools/` 目录下放置 Python 脚本，使用 `@tool` 装饰器定义工具，LLM 可在推理过程中主动调用：

**目录结构：**
```
custom-tools/
├── web_search.py
└── calculator.py
```

**工具脚本示例：**
```python
# custom-tools/web_search.py
from langchain_core.tools import tool

@tool
def web_search(query: str) -> str:
    """Search the web for information. Use when you need current data."""
    # 实现逻辑
    return result
```

**配置声明：**
```yaml
# AGENTS.md
tools:
  - Read
  - Bash
  - web_search    # 自定义工具名（对应 custom-tools/web_search.py）
```

**加载流程：**
1. `CustomToolManager` 扫描 `custom-tools/` 目录
2. 使用 `importlib` 动态加载 Python 模块
3. 提取所有 `@tool` 装饰的函数（`StructuredTool` 实例）
4. 合并到 `AgentRuntime._get_available_tools()` 返回的工具列表
5. DeepAgents 创建 agent 时注入工具，LLM 可主动调用

**工具发现优先级：**
1. 内建工具（bash/read/edit/grep）
2. 自定义工具（custom-tools/）
3. MCP 工具（mcp-configs/）

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
│           └── tool.py        # Python 实现 (必须有 main() 函数)
├── custom-tools/              # 可选：自定义工具
│   └── <tool-name>.py         # @tool 装饰的 Python 脚本
└── mcp-configs/               # 可选：MCP 服务器
    └── <server-name>/
        ├── ActiveMCP.json     # 工具选择
        └── config.yaml        # 连接配置
```

### 4.2 AGENTS.md 示例

```yaml
---
name: "Full Test Agent"
vendorKey: "test"
agentKey: "full-agent"
version: "1.0.0"
slug: "test/full-agent"
description: "A full-featured test agent"
author: "@test"
license: "MIT"

skills:
  - name: "bash-tool"
    source: "local"
    version: "1.0.0"
    required: true

mcpServers:
  - vendor: "block"
    server: "filesystem"
    version: "1.0.0"
    configDir: "mcp-configs/filesystem"
    required: false

tools:
  - Read
  - Bash
  - Edit
  - Grep
  - web_search              # 自定义工具 (custom-tools/web_search.py)

config:
  temperature: 0.7
  max_tokens: 4096
---

# Agent Purpose
You are a test agent for E2E testing.

## Core Responsibilities
- Execute bash commands
- Read and edit files
```

### 4.3 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `AGENT_CONFIG_DIR` | `/config` | OAF 配置目录 |
| `LLM_API_KEY` | (空) | LLM API Key |
| `LLM_MODEL_ID` | (空) | 模型 ID |
| `LLM_BASE_URL` | (空) | LLM API 地址 |
| `SERVER_HOST` | `0.0.0.0` | 服务监听地址 |
| `SERVER_PORT` | `8100` | 服务端口 |
| `GUNICORN_WORKERS` | `4` | gunicorn worker 进程数 (仅 Docker) |
| `GUNICORN_MAX_REQUESTS` | `1000` | worker 自动重启请求数 |
| `GUNICORN_MAX_REQUESTS_JITTER` | `50` | 重启请求数抖动 |

---

## 5. API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 服务信息 |
| GET | `/health` | 健康检查 |
| GET | `/.well-known/agent-card.json` | Agent Card |
| GET | `/skills` | 技能列表 |
| GET | `/mcp` | MCP 列表 |
| GET | `/tools` | 工具列表 (内建 + 自定义 + MCP) |
| POST | `/mcp/resources/read` | MCP Apps Host — 获取 UI 资源 |
| POST | `/mcp/tools/list` | MCP 工具列表 |
| GET | `/debug` | 调试页面 (MCP Apps Host) |
| POST | `/` | JSON-RPC 2.0 (message/send, message/stream, threads/*) |
| POST | `/tasks` | REST 任务创建 |
| GET | `/tasks/{id}` | REST 任务查询 |
| GET | `/tasks` | REST 任务列表 |
| GET | `/threads` | Thread 列表 (checkpoint) |
| GET | `/threads/{id}` | Thread 对话历史 |
| DELETE | `/threads/{id}` | 删除 Thread |

---

## 6. 依赖清单

| 依赖 | 版本 | 用途 |
|------|------|------|
| deepagents | >=0.5.0 | Agent 框架内核 |
| langchain | >=1.0.0 | LLM 抽象层 |
| langchain-openai | >=1.0.0 | OpenAI 兼容 API 客户端 |
| langchain-mcp-adapters | >=0.2.0 | MCP 客户端 (SSE transport) |
| langgraph | >=1.2.0 | Agent 工作流编排 + checkpoint |
| langgraph-checkpoint-mysql | >=3.0.0 | MySQL checkpoint 存储 |
| asyncmy | >=0.2.10 | 异步 MySQL 驱动 |
| fastapi | >=0.100.0 | HTTP 服务 |
| uvicorn | >=0.30.0 | ASGI 服务器 (开发模式) |
| gunicorn | >=23.0.0 | WSGI 服务器 (生产模式) |
| pydantic | >=2.0.0 | 数据验证 |
| pyyaml | >=6.0.0 | YAML 解析 |
| httpx | >=0.25.0 | HTTP 客户端 (LLM API 调用) |
| python-dotenv | >=1.0.0 | 环境变量加载 |
| sse-starlette | >=2.0.0 | SSE 支持 |

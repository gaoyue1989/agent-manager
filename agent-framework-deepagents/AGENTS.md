# Agent Framework — AGENTS.md

## 二级模块概述

Agent Framework 是基于 **DeepAgents** 的独立可运行 Agent 服务框架。支持 **OAF v0.8.0** 配置规范 (`AGENTS.md` frontmatter)、**A2A v1.0.0** 通信协议 (JSON-RPC + SSE) 和 **A2UI v0.8** 声明式 UI 扩展。通过 MySQL checkpoint 实现 thread_id 会话持久化，支持 MCP 工具集成。

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

## 技术栈

| 层级 | 技术选型 |
|------|---------|
| Agent 引擎 | DeepAgents 0.6.1 (langchain-ai/deepagents) |
| 工作流编排 | LangGraph 1.2.0 |
| LLM 适配 | ChatOpenAIReasoning (GLM-5 reasoning_content 兼容) |
| 服务框架 | FastAPI + Uvicorn (port 8100) |
| 配置解析 | Pydantic 2.x (AppConfig / LLMConfig / MySQLCheckpointConfig) |
| Checkpoint 持久化 | langgraph-checkpoint-mysql 3.0.0 + asyncmy |
| MCP 集成 | langchain-mcp-adapters 0.2.2 (MultiServerMCPClient + SSE) |
| A2UI | JSONL 声明式 UI 扩展 |
| 对象存储 | MinIO (端口 9000/9001) |
| 数据库 | GreatSQL 8.0 (端口 3307, DB `agent_manager_test`) |

---

## 目录结构

```
agent-framework/
├── server/                         # Python 服务代码
│   ├── app.py                      # FastAPI 应用工厂 + lifespan
│   ├── main.py                     # 启动入口 (uvicorn)
│   ├── config.py                   # 配置 (AppConfig / LLMConfig / MySQLCheckpointConfig)
│   ├── models/                     # 数据模型
│   │   ├── oaf_types.py            # OAF v0.8.0 类型 (OAFConfig, SkillConfig, MCPServerConfig)
│   │   └── a2a_types.py            # A2A 协议类型
│   ├── services/                   # 业务服务
│   │   ├── oaf_loader.py           # AGENTS.md 解析 (frontmatter + body)
│   │   ├── agent_runtime.py        # DeepAgents 运行时 (invoke/stream/tools/thread CRUD)
│   │   ├── checkpoint_manager.py   # MySQL checkpoint 生命周期管理
│   │   ├── skill_manager.py        # Skill 动态加载 (importlib)
│   │   ├── mcp_manager.py          # MCP 配置加载 + MultiServerMCPClient
│   │   ├── a2ui_service.py         # A2UI JSONL 生成
│   │   ├── chat_model.py           # ChatOpenAIReasoning (GLM-5 适配)
│   │   ├── custom_tool_manager.py  # 自定义工具动态加载 (importlib)
│   │   └── llm_logger.py           # LLM 请求/响应内存日志器 (按 thread_id 存储)
│   ├── callbacks/                  # LangChain 回调
│   │   └── llm_callback.py         # LLMLoggingCallback (on_chat_model_start / on_llm_end)
│   ├── routes/                     # 路由
│   │   ├── a2a_routes.py           # A2A 端点 (JSON-RPC + SSE + thread 方法)
│   │   ├── thread_routes.py        # Thread REST (GET/DELETE /threads)
│   │   ├── agent_card.py           # Agent Card 发现
│   │   └── debug_ui.py             # 内嵌调试页面
│   └── templates/
│       └── debug_page.html         # 调试页面 (单文件, SSE + A2UI + tool_call 可视化)
├── tests/
│   ├── conftest.py                 # 测试 fixtures + .env.test 加载
│   ├── unit/                       # 单元测试 (81 个)
│   ├── integration/                # 集成测试 (20 个)
│   ├── e2e/                        # E2E 测试 (17 个, 需要 LLM)
│   │   └── e2e-screenshots.js       # Debug 页面截图测试 (Puppeteer)
│   └── fixtures/                   # 测试 Agent 配置
│       ├── minimal-agent/          # 最小化配置
│       └── full-agent/             # 完整配置 (skills + MCP + tools)
│           ├── AGENTS.md
│           ├── skills/bash-tool/
│           ├── custom-tools/       # 自定义工具脚本
│           │   └── echo.py
│           └── mcp-configs/
│               ├── filesystem/     # MCP 文件系统
│               ├── weather/        # MCP 天气查询
│               └── mcp_servers.py  # MCP SSE mock server
├── docs/                           # 文档
│   ├── agent-framework-design.md
│   ├── agent-framework-deploy.md
│   ├── agent-framework-test.md
│   └── checkpoint-design.md
├── requirements.txt                # Python 依赖
├── Dockerfile                      # Docker 镜像构建
├── docker-compose.yml              # Docker Compose 编排
├── Makefile                        # 构建/测试/运行
└── .env.example                    # 环境变量模板
```

---

## 核心模块

### 1. checkpoint_manager.py — MySQL Checkpoint 持久化

```python
class CheckpointManager:
    def __init__(self, dsn: str)              # DSN 解析
    async def start(self) -> AsyncMySaver      # 连接 + 建表
    async def close(self)                     # 关闭连接
    @property saver  → AsyncMySaver
    async def delete_thread_by_ns(thread_id, checkpoint_ns)  # 多租户删除
```

MySQL 表: `checkpoints`, `checkpoint_blobs`, `checkpoint_writes`, `checkpoint_migrations`

**多租户支持**: 通过 `checkpoint_ns` 字段隔离不同 Agent 的数据，主键为 `(thread_id, checkpoint_ns, checkpoint_id)`。

### 2. agent_runtime.py — DeepAgents 运行时

```python
class AgentRuntime:
    # 核心方法
    async def invoke(message, thread_id)           → (response_text, thread_id)
    async def invoke_stream(message, thread_id)    → AsyncGenerator[Event]
    async def get_thread_state(thread_id)          → 完整对话历史
    async def delete_thread(thread_id)             → 删除 checkpoint 数据
    async def list_threads()                       → 聚合 thread 列表
    async def invoke_skill(name, input)            → Skill 调用

    # 内部
    _ensure_agent()        → create_deep_agent(checkpointer=saver)  # 懒加载, 注入 checkpointer
    _get_available_tools() → 内建工具 (bash/read/edit/grep) + MCP 工具 (_mcp_tools 缓存)
    _get_callbacks()       → LLMLoggingCallback 列表 (注入 config["callbacks"])

    # LLM 日志
    _llm_logger            → LLMLogger 实例 (注入 callback 记录每次 LLM 请求/响应)
    _log_direct_call()     → 降级路径 (_invoke_direct) 手动记录 LLM 调用

    # 多租户
    @property checkpoint_ns → agent slug (vendorKey/agentKey)
```

agent 创建时机: 首次 `invoke`/`invoke_stream` 调用 (`_ensure_agent` 懒加载)

**多租户支持**: 所有 checkpoint 操作传递 `checkpoint_ns`，实现不同 Agent 数据隔离。

### 3. mcp_manager.py — MCP 管理

```python
class MCPManager:
    def load_configs(mcp_servers)              → 从 configDir 加载 ActiveMCP.json + config.yaml
    async def create_mcp_client(configs)       → MultiServerMCPClient
    def get_mcp_summaries(configs)             → `/mcp` 端点摘要
```

MCP 工具预加载: `app.py` lifespan → `mcp_client.get_tools()` → `agent_runtime._mcp_tools`

### 4. a2a_routes.py — A2A 端点

| JSON-RPC 方法 | 说明 |
|------|------|
| `message/send` | 同步消息 |
| `message/stream` | SSE 流式消息 (含 tool_call/tool_result 事件) |
| `threads/list` / `threads/get` / `threads/delete` / `threads/create` | Thread 管理 |

SSE 事件流: `task_update(working)` → `token*` → `tool_call*` → `tool_result*` → `task_update(completed)` → `done`

### 5. thread_routes.py — Thread REST API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/threads` | 列出所有 thread |
| GET | `/threads/{id}` | 获取对话历史 |
| DELETE | `/threads/{id}` | 删除 thread |

### 6. custom_tool_manager.py — 自定义工具管理

```python
class CustomToolManager:
    def __init__(self, custom_tools_dir)        # 初始化目录
    def load_tools(tool_names)                  → list[StructuredTool]  # 按名称加载
    def load_all_tools()                        → list[StructuredTool]  # 加载所有
    def get_available_tool_names()              → list[str]             # 可用工具名
    def get_tool_summaries(loaded_tools)        → list[dict]            # 工具摘要
```

工具脚本格式: `custom-tools/{name}.py`，使用 `@tool` 装饰器定义函数。

### 7. llm_logger.py — LLM 请求/响应日志

```python
class LLMLogger:
    def __init__(self, max_calls_per_thread=50)    # 内存日志器
    def log_call(thread_id, request_info, response_info)  → call_id
    def get_calls(thread_id)                       → list[dict]  # 返回所有 LLM 调用记录
    def clear_thread(thread_id)                    # 清除某线程的日志
```

每条记录包含 `request` (messages/model/params) 和 `response` (content/tool_calls/usage/elapsed)。

### 8. callbacks/llm_callback.py — LangChain 回调

```python
class LLMLoggingCallback(BaseCallbackHandler):
    def on_chat_model_start(serialized, messages, ...)  # 捕获发送给 LLM 的消息
    def on_llm_end(response, ...)                       # 捕获 LLM 响应
```

通过 `config["callbacks"]` 注入到 DeepAgents 的 `ainvoke/astream` 调用中，自动拦截每次 LLM 调用。

---

## 配置

### 环境变量

| 变量 | 默认值 | 必填 | 说明 |
|------|--------|------|------|
| `LLM_API_KEY` | — | ✓ | LLM API 密钥 |
| `LLM_MODEL_ID` | — | ✓ | 模型 ID |
| `LLM_BASE_URL` | — | ✓ | LLM API 端点 |
| `LLM_PROVIDER` | `openai` | | 提供商标识 |
| `LLM_TEMPERATURE` | `0.7` | | 生成温度 |
| `LLM_MAX_TOKENS` | `4096` | | 最大 token |
| `LLM_TIMEOUT` | `120` | | 超时(秒) |
| `AGENT_CONFIG_DIR` | `/config` | | Agent 配置目录 |
| `SERVER_HOST` | `0.0.0.0` | | 监听地址 |
| `SERVER_PORT` | `8100` | | 服务端口 |
| `CHECKPOINT_MYSQL_DSN` | `mysql+asyncmy://...` | | MySQL checkpoint DSN |

### DSN 格式

```
mysql+asyncmy://{user}:{password}@[{host}]:{port}/{database}
```
密码特殊字符需 URL 编码 (`@` → `%40`)。

### OAF 配置 (AGENTS.md)

```yaml
---
name: "Full Test Agent"
vendorKey: "test"
agentKey: "full-agent"
version: "1.0.0"
slug: "test/full-agent"

skills:
  - name: "bash-tool"
    source: "local"
    version: "1.0.0"

mcpServers:
  - vendor: "weather"
    server: "weather"
    version: "1.0.0"
    configDir: "mcp-configs/weather"

tools:
  - Read
  - Bash
  - Edit
  - Grep
  - echo                    # 自定义工具 (custom-tools/echo.py)
---
# System prompt Markdown body
```

---

## 服务端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 服务信息 + 协议声明 |
| GET | `/health` | 健康检查 (含 checkpoint/mcp status + tenant_prefix) |
| GET | `/.well-known/agent-card.json` | Agent Card 发现 |
| GET | `/skills` | 技能列表 |
| GET | `/mcp` | MCP 服务器列表 |
| GET | `/tools` | 工具列表 (内建 + 自定义 + MCP) |
| GET | `/debug` | 调试页面 |
| POST | `/` | JSON-RPC 2.0 |
| GET | `/threads` | Thread 列表 (按 checkpoint_ns 过滤) |
| GET | `/threads/{id}` | Thread 对话历史 |
| DELETE | `/threads/{id}` | 删除 Thread (按 checkpoint_ns 隔离) |
| GET | `/system-prompt` | 系统提示词 (含自动生成的 Skills/MCP 上下文) |
| GET | `/threads/{id}/llm-calls` | 某 Thread 的所有 LLM 请求/响应日志 |

---

## 调试页面功能 ( `/debug` )

| 功能 | 说明 |
|------|------|
| Agent 信息 | 名称、MCP 数量 |
| Tools 列表 | 内建工具 + 自定义工具 + MCP 工具 |
| Skills 列表 | 技能名称和描述 |
| System Prompt 查看 | 区分 Base (AGENTS.md) 和 Auto-generated (Skills/MCP Context) |
| MCP Servers | 已配置的 MCP 服务器列表 |
| Thread 管理 | 创建/切换/查看对话历史 |
| 流式对话 | SSE 实时 token + tool_call/tool_result 可视化 |
| MCP Apps 渲染 | tool_call 含 `_meta.ui.resourceUri` 时渲染 iframe |
| **LLM Calls** | 查看每轮对话的 LLM 请求/响应详情 |
| Token 统计 | 显示每次对话的输入/输出/总计 token + 耗时 |

## LLM 请求/响应日志

`LLMLogger` 按 thread_id 存储每次 LLM 调用的完整信息：

| 字段 | 说明 |
|------|------|
| `request.messages` | 发送给模型的消息列表 (System/Human/AI/Tool) |
| `request.model` | 模型 ID |
| `request.params` | temperature / max_tokens |
| `response.content` | 模型回复文本 |
| `response.tool_calls` | 工具调用列表 (id/name/args) |
| `response.usage` | token 用量 (input/output/total) |
| `response.elapsed` | 调用耗时 (秒) |

**实现方式**：通过 `LLMLoggingCallback` (LangChain `BaseCallbackHandler`) 自动拦截 DeepAgents 的每次 LLM 调用，无需修改 agent 内部逻辑。降级路径 (`_invoke_direct`) 中手动记录。

**容量控制**：每 thread 最多保留 50 条记录，防止内存膨胀。

---

## 启动

```bash
cd agent-framework
cp .env.example .env  # 编辑填入 LLM_API_KEY 等

# 本地开发
AGENT_CONFIG_DIR=tests/fixtures/full-agent python -m uvicorn server.app:create_app --factory --host 0.0.0.0 --port 8100

# 访问调试页面
open http://localhost:8100/debug
```

---

## 测试

```bash
# 全部测试
pytest tests/ -v

# 按层
pytest tests/unit/ -v          # 81 单元测试
pytest tests/integration/ -v   # 20 集成测试 (需要 MySQL)
pytest tests/e2e/ -v           # 17 E2E 测试 (需要 LLM)

# MySQL checkpoint 需要
CHECKPOINT_MYSQL_DSN=mysql+asyncmy://agent_manager:Agent%40Manager2026@127.0.0.1:3307/agent_manager_test
```

---

## 多租户支持

### 数据隔离机制

使用 **thread_id 前缀** 实现多租户隔离：

| 字段 | 用途 | 示例 |
|------|------|------|
| `thread_id` | 完整标识 (含租户前缀) | `tenant-a/agent-x:uuid-xxx` |
| `checkpoint_ns` | LangGraph 内部使用 | `""` (空字符串) |

**实际 thread_id 格式**: `{tenant_prefix}:{original_thread_id}`

其中 `tenant_prefix = vendorKey/agentKey` (来自 OAF 配置的 slug)

### 隔离效果

两个 Agent 即使使用相同的 `thread_id`，数据也完全隔离：

| Agent A | Agent B |
|---------|---------|
| `tenant_prefix = "tenant-a/agent-x"` | `tenant_prefix = "tenant-b/agent-y"` |
| `original_thread_id = "thread-1"` | `original_thread_id = "thread-1"` |
| **实际 thread_id**: `"tenant-a/agent-x:thread-1"` | **实际 thread_id**: `"tenant-b/agent-y:thread-1"` |
| **完全隔离** | **完全隔离** |

### 实现细节

1. **AgentRuntime** 初始化时从 `oaf_config.slug` 获取 `tenant_prefix`
2. `invoke`/`invoke_stream` 时将 `thread_id` 转换为 `{tenant_prefix}:{thread_id}`
3. `get_thread_state` 时同样转换 `thread_id`
4. `list_threads` 按前缀过滤，只返回当前 Agent 的 threads，并去除前缀返回原始 thread_id
5. `delete_thread` 按完整 thread_id 删除

### 为什么不使用 checkpoint_ns

LangGraph 的 `checkpoint_ns` 用于 subgraph 命名空间，在 root graph 中会被覆盖为空字符串。
因此采用 **thread_id 前缀** 方案实现多租户隔离，更简单可靠。

### StoreBackend NamespaceFactory

`StoreBackend.NamespaceFactory` 用于文件存储隔离（非 checkpoint 隔离）：

```python
# 基于用户身份隔离文件
namespace=lambda rt: (rt.server_info.user.identity, "filesystem")

# 基于 checkpoint_ns 隔离文件
namespace=lambda rt: (rt.execution_info.checkpoint_ns, "files")
```

---

## 已知问题

1. `test_sse_streaming_events` — SSE 流末尾缺少 `event: done`（DeepAgents `stream_mode="messages"` 行为变更）
2. MCP SSE mock server 心跳 `: heartbeat` 触发 pydantic 解析警告（不影响功能）
3. GLM-5 流式 tool_call 发送 args 值为空字符串，需从 checkpoint 补全

---

## 自定义工具

在 `custom-tools/` 目录下放置 Python 脚本，使用 `@tool` 装饰器定义工具：

```
custom-tools/
├── web_search.py
└── calculator.py
```

```python
# custom-tools/web_search.py
from langchain_core.tools import tool

@tool
def web_search(query: str) -> str:
    """Search the web for information."""
    # 实现逻辑
    return result
```

在 `AGENTS.md` 的 `tools` 字段中声明工具名（不含 `.py` 后缀）：

```yaml
tools:
  - Read
  - Bash
  - web_search    # 自定义工具
```

---

## 依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| deepagents | >=0.6.0 | Agent 框架内核 |
| langchain | >=1.0.0 | LLM 抽象层 |
| langchain-openai | >=1.0.0 | OpenAI 兼容 API |
| langgraph | >=1.2.0 | 工作流编排 + checkpoint |
| langgraph-checkpoint-mysql | >=3.0.0 | MySQL checkpoint 存储 |
| langchain-mcp-adapters | >=0.2.0 | MCP 客户端 |
| asyncmy | >=0.2.10 | 异步 MySQL 驱动 |
| fastapi | >=0.100.0 | HTTP 服务 |
| uvicorn | >=0.30.0 | ASGI 服务器 |
| pydantic | >=2.0.0 | 数据验证 |
| pyyaml | >=6.0.0 | YAML 解析 |
| httpx | >=0.27.0 | HTTP 客户端 |

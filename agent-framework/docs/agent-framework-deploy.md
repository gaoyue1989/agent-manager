# Agent Framework — 部署文档

**版本:** v1.2.0
**日期:** 2026-05-17

---

## 1. 前置条件

| 条件 | 说明 |
|------|------|
| Python | ≥ 3.11 |
| MySQL | ≥ 8.0 (GreatSQL 3307), 用于 thread_id checkpoint 持久化 |
| Docker | ≥ 24.0 (Docker 部署时需要) |
| LLM API | OpenAI 兼容接口 |

---

## 2. 快速启动 (本地开发)

### 2.1 安装依赖

```bash
cd agent-framework
python -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

### 2.2 准备配置目录

```bash
mkdir -p config
```

创建 `config/AGENTS.md`:

```yaml
---
name: "My Agent"
vendorKey: "myorg"
agentKey: "my-agent"
version: "1.0.0"
slug: "myorg/my-agent"
description: "A custom agent"
author: "@myorg"
license: "MIT"
tags: ["demo"]
tools:
  - Read
  - Bash
---
# Agent Purpose
You are a helpful AI assistant.
```

### 2.3 创建 .env 文件

```bash
cp .env.example .env
```

编辑 `.env`:

```bash
LLM_API_KEY=your_api_key
LLM_MODEL_ID=your_model_id
LLM_BASE_URL=https://your-api-endpoint/v1
AGENT_CONFIG_DIR=./config
CHECKPOINT_MYSQL_DSN=mysql+asyncmy://agent_manager:Agent%40Manager2026@127.0.0.1:3307/agent_manager_test
```

### 2.4 本地启动 (开发模式)

```bash
AGENT_CONFIG_DIR=./config python -m uvicorn server.app:create_app --factory --host 0.0.0.0 --port 8100
```

---

## 3. Docker 部署

### 3.1 目录结构

```
agent-framework/
├── Dockerfile         # gunicorn + uvicorn worker 生产启动
├── docker-compose.yml # 服务编排
├── server/
│   ├── app.py         # FastAPI 应用工厂
│   ├── wsgi.py        # gunicorn 入口 (调用 create_app)
│   └── ...
└── config/            # 挂载到容器 /config
    ├── AGENTS.md
    ├── skills/
    └── mcp-configs/
```

### 3.2 配置环境变量

创建 `.env` 文件 (与 `docker-compose.yml` 同级):

```bash
LLM_API_KEY=your_api_key
LLM_MODEL_ID=your_model_id
LLM_BASE_URL=https://your-api-endpoint/v1
```

### 3.3 构建镜像

```bash
docker build -t agent-framework:latest .
```

镜像大小约 950 MB，首次构建耗时约 2-3 分钟。

### 3.4 启动服务

```bash
docker compose up -d
```

启动后等待 healthy 状态：

```bash
$ docker ps --filter name=agent-framework
CONTAINER ID   STATUS
abc123def456   Up 10 seconds (healthy)
```

### 3.5 查看日志

```bash
docker logs agent-framework-agent-framework-1
```

预期输出：

```
Loaded OAF: My Agent v1.0.0
  Skills: 0 - []
  MCP: 0 - []
  Tools: ['Read', 'Bash', 'Edit', 'Grep']
Connecting to MySQL checkpoint: mysql+asyncmy://agent_manager:***@127.0.0.1:3307/agent_manager_test
Checkpoint tables ready
[INFO] Started server process [7]
[INFO] Application startup complete.
```

### 3.6 停止

```bash
docker compose down
```

### 3.7 容器内文件布局

```
/app/
├── server/                    # Python 服务代码 (构建时复制)
│   ├── app.py                 # FastAPI 应用工厂
│   ├── wsgi.py                # gunicorn 入口
│   └── ...
└── /config/                   # 运行时挂载
    ├── AGENTS.md              # (必需)
    ├── skills/                # (可选)
    └── mcp-configs/           # (可选)
```

---

## 4. 配置参考 (完整)

### 4.1 环境变量

| 变量 | 类型 | 默认值 | 必填 | 说明 |
|------|------|--------|------|------|
| `LLM_API_KEY` | string | — | ✓ | LLM API 密钥 |
| `LLM_MODEL_ID` | string | — | ✓ | 模型 ID |
| `LLM_BASE_URL` | string | — | ✓ | LLM API 端点 URL |
| `LLM_PROVIDER` | string | `openai` | | 模型提供商标识 |
| `LLM_TEMPERATURE` | float | `0.7` | | 生成温度 (0.0-1.0) |
| `LLM_MAX_TOKENS` | int | `4096` | | 最大输出 token 数 |
| `LLM_TIMEOUT` | int | `120` | | API 调用超时(秒) |
| `AGENT_CONFIG_DIR` | path | `/config` | | OAF 配置目录路径 |
| `SERVER_HOST` | string | `0.0.0.0` | | 服务监听地址 |
| `SERVER_PORT` | int | `8100` | | 服务端口 |
| `SERVER_RELOAD` | bool | `false` | | 热重载 (开发用) |
| `CHECKPOINT_MYSQL_DSN` | string | `mysql+asyncmy://agent_manager:Agent%40Manager2026@127.0.0.1:3307/agent_manager_test` | | MySQL checkpoint DSN (thread_id 持久化) |
| `GUNICORN_WORKERS` | int | `4` | | gunicorn worker 进程数 |
| `GUNICORN_MAX_REQUESTS` | int | `1000` | | worker 自动重启请求数 |
| `GUNICORN_MAX_REQUESTS_JITTER` | int | `50` | | 重启请求数抖动 |

### 4.2 AGENTS.md — 身份字段 (必填)

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | string | Agent 显示名称 (1-100 字符) |
| `vendorKey` | string | 发布者命名空间 (kebab-case) |
| `agentKey` | string | Agent 标识符 (kebab-case) |
| `version` | string | 语义版本号 (如 "1.0.0") |
| `slug` | string | 唯一标识: `vendorKey/agentKey` |
| `description` | string | 简要描述 (50-500 字符) |
| `author` | string | 作者标识 (如 "@vendor") |
| `license` | string | SPDX 许可证 (如 "MIT") |
| `tags` | list[string] | 分类标签 |

### 4.3 AGENTS.md — 工具字段

| 字段 | 类型 | 值 | 说明 |
|------|------|------|------|
| `tools` | list[string] | `Read`, `Bash`, `Edit`, `Grep` | 启用内建工具 |
| | | `Read` | 文件读取工具 |
| | | `Bash` | Bash 命令执行工具 |
| | | `Edit` | 文件编辑工具 (字符串替换) |
| | | `Grep` | 文件内容搜索工具 |

### 4.4 AGENTS.md — 配置字段

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `config.temperature` | float | `0.7` | 模型生成温度 |
| `config.max_tokens` | int | `4096` | 最大输出 token |
| `config.require_confirmation` | bool | `false` | 操作前需确认 |

### 4.5 AGENTS.md — 模型字段

| 格式 | 字段 | 类型 | 说明 |
|------|------|------|------|
| 完整格式 | `model.provider` | string | 模型提供商 |
| | `model.name` | string | 模型名称 |
| | `model.embedding` | string | 嵌入模型名称 |
| 简化格式 | `model` | string | 别名 (如 `"sonnet"`, `"opus"`, `"haiku"`) |

### 4.6 AGENTS.md — Skills 字段

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `skills[].name` | string | — | Skill 标识符 |
| `skills[].source` | string | `"local"` | `"local"` 或 well-known URL |
| `skills[].version` | string | `"1.0.0"` | 语义版本 |
| `skills[].required` | bool | `false` | 是否必需 |

### 4.7 AGENTS.md — MCP 服务器字段

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `mcpServers[].vendor` | string | `""` | MCP 提供商命名空间 |
| `mcpServers[].server` | string | `""` | 服务器标识符 |
| `mcpServers[].version` | string | `"1.0.0"` | 语义版本 |
| `mcpServers[].configDir` | string | `""` | 配置子目录路径 |
| `mcpServers[].required` | bool | `false` | 是否必需 |

### 4.8 AGENTS.md — 子 Agent 字段

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `agents[].vendor` | string | `""` | Agent 提供商 |
| `agents[].agent` | string | `""` | Agent 标识符 |
| `agents[].version` | string | `"1.0.0"` | 语义版本 |
| `agents[].role` | string | `""` | 角色 (如 "reviewer") |
| `agents[].delegations` | list[string] | `[]` | 委托任务列表 |
| `agents[].required` | bool | `false` | 是否必需 |
| `agents[].endpoint` | string | `""` | A2A 端点 URL |

### 4.9 AGENTS.md — 内存字段

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `memory.type` | string | `"editable"` | 类型: `"editable"` 或 `"read-only"` |
| `memory.blocks` | dict | `{}` | 内存块定义 |

### 4.10 AGENTS.md — 编排字段

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `orchestration.entrypoint` | string | `"main"` | 主入口 Agent |
| `orchestration.fallback` | string | `""` | 失败回退 Agent |
| `orchestration.triggers` | list[dict] | `[]` | 事件-动作映射 |

### 4.11 AGENTS.md — Harness 配置字段

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `harnessConfig.deep-agents.a2a.protocol` | string | — | A2A 协议版本 |
| `harnessConfig.deep-agents.a2a.bindings` | list[string] | — | 绑定方式: `jsonrpc`, `rest` |
| `harnessConfig.deep-agents.a2a.streaming` | bool | — | 支持流式传输 |
| `harnessConfig.deep-agents.a2ui.enabled` | bool | `true` | 启用 A2UI |
| `harnessConfig.deep-agents.a2ui.version` | string | — | A2UI 版本 |
| `harnessConfig.deep-agents.a2ui.catalog_id` | string | — | UI catalog ID URL |

### 4.12 Markdown Body — 系统提示词

`AGENTS.md` 中 `---` 之后的 Markdown 正文即为 Agent 的系统提示词，会被注入到每次 LLM 调用的上下文中。

---

## 5. Skills 配置

### 5.1 目录结构

```
config/skills/<skill-name>/
├── SKILL.md                   # Skill 清单 (必需)
├── scripts/
│   └── tool.py                # Python 实现 (必需)
├── resources/                 # 可选: 数据文件
└── assets/                    # 可选: 图片资源
```

### 5.2 SKILL.md 字段 (完整)

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | ✓ | Skill 名称 |
| `description` | string | ✓ | Skill 功能描述 |
| `license` | string | | 许可证 (如 "MIT") |
| `metadata.author` | string | | 作者 |
| `metadata.version` | string | | 版本号 |
| `allowed-tools` | list[string] | | 允许使用的工具: `bash`, `python` |

### 5.3 tool.py 规范

```python
def main(input_data: str = None) -> str:
    """入口函数，接收字符串输入，返回字符串输出"""
    # 实现 Skill 逻辑
    return "result"
```

---

## 6. MCP 服务器配置

### 6.1 目录结构

```
config/mcp-configs/<server-name>/
├── ActiveMCP.json             # 工具选择 (必需)
└── config.yaml                # 连接配置 (必需)
```

### 6.2 ActiveMCP.json 字段 (完整)

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `vendor` | string | | MCP 提供商 |
| `server` | string | | 服务器名称 |
| `version` | string | | 版本号 |
| `selectedTools` | list[object] | ✓ | 启用的工具列表 |
| `selectedTools[].name` | string | ✓ | 工具名称 |
| `selectedTools[].enabled` | bool | ✓ | 是否启用 |
| `selectedTools[].description` | string | | 工具描述 |
| `selectedTools[].required` | bool | | 是否必需 |
| `excludedTools` | list[string] | | 明确排除的工具 |
| `contextStrategy` | string | | `"subset"` 或 `"all"` |

### 6.3 config.yaml 字段 (完整)

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `vendor` | string | | MCP 提供商 |
| `server` | string | | 服务器名称 |
| `version` | string | | 版本号 |
| `connection.type` | string | ✓ | 连接协议: `"sse"`, `"http"`, `"stdio"` |
| `connection.url` | string | ✓ | 服务器 URL |
| `connection.timeout` | int | | 超时时间(秒) |
| `auth.type` | string | | 认证方式: `"bearer"`, `"api-key"`, `"oauth"` |
| `auth.token` | string | | 认证令牌 (支持 `${ENV_VAR}`) |
| `permissions.allow_paths` | list[string] | | 允许路径 |
| `permissions.deny_paths` | list[string] | | 禁止路径 |
| `permissions.max_file_size` | string | | 最大文件大小 |
| `permissions.read_only` | bool | | 只读模式 |
| `rate_limit.requests_per_minute` | int | | 每分钟请求限制 |
| `rate_limit.burst` | int | | 突发请求限制 |

---

## 7. 服务端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 服务信息 |
| GET | `/health` | 健康检查 |
| GET | `/.well-known/agent-card.json` | Agent Card 发现 |
| GET | `/skills` | 技能列表 |
| GET | `/mcp` | MCP 服务器列表 |
| POST | `/mcp/resources/read` | MCP Apps Host — 获取 UI 资源 (server + uri) |
| POST | `/mcp/tools/list` | MCP 工具列表查询 |
| GET | `/debug` | 调试页面 (MCP Apps Host 前端) |
| POST | `/` | JSON-RPC 2.0 |
| POST | `/tasks` | REST 消息 |
| GET | `/tasks/{id}` | REST 任务查询 |
| GET | `/tasks` | REST 任务列表 |
| GET | `/threads` | Thread 列表 (checkpoint 持久化) |
| GET | `/threads/{id}` | Thread 对话历史 (含 tool_call) |
| DELETE | `/threads/{id}` | 删除 Thread 及持久化数据 |

---

## 8. API 验证

部署完成后，可通过以下命令验证各端点是否正常。

### 8.1 健康检查

```bash
$ curl -s http://localhost:8100/health | python3 -m json.tool
```
```json
{
    "status": "healthy",
    "agent": "Agent Framework Test",
    "version": "1.0.0",
    "skills": 0,
    "mcp_servers": 0,
    "llm_configured": true,
    "checkpoint": true
}
```

### 8.2 Agent Card 发现

```bash
$ curl -s http://localhost:8100/.well-known/agent-card.json | python3 -m json.tool
```
```json
{
    "name": "Agent Framework Test",
    "url": "http://0.0.0.0:8100/",
    "version": "1.0.0",
    "capabilities": {
        "streaming": true,
        "stateTransitionHistory": true
    },
    "skills": [...],
    "extensions": [
        {
            "uri": "https://a2ui.org/a2a-extension/a2ui/v0.8"
        }
    ]
}
```

### 8.3 同步消息 (message/send)

```bash
$ curl -s -X POST http://localhost:8100/ \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"message/send","id":"1","params":{"message":{"role":"user","text":"say hello"}}}' | python3 -m json.tool
```
```json
{
    "jsonrpc": "2.0",
    "result": {
        "id": "<task-uuid>",
        "status": { "state": "completed" },
        "artifacts": [
            {
                "name": "response",
                "parts": [{ "text": "Hello! ..." }]
            }
        ],
        "history": [...]
    },
    "id": "1"
}
```

### 8.4 流式消息 (message/stream, SSE)

```bash
$ curl -s -X POST http://localhost:8100/ \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"message/stream","id":"2","params":{"message":{"role":"user","text":"count from 1 to 3"}}}'
```
```
event: task_update
data: {"id": "<uuid>", "state": "working"}

event: token
data: {"token": "1", "task_id": "<uuid>"}

event: token
data: {"token": ",", "task_id": "<uuid>"}
...
```

### 8.5 MCP Apps Host — 获取 UI 资源

```bash
$ curl -s -X POST http://localhost:8100/mcp/resources/read \
  -H "Content-Type: application/json" \
  -d '{"server":"weather","uri":"ui://weather/weather-card"}' | python3 -m json.tool | head -10
```
```json
{
    "contents": [
        {
            "uri": "ui://weather/weather-card",
            "mimeType": "text/html;profile=mcp-app",
            "text": "<!DOCTYPE html>..."
        }
    ]
}
```

### 8.6 调试页面

浏览器访问 `http://localhost:8100/debug`，支持 SSE 实时流式消息和 MCP Apps Host 渲染。

### 8.7 Thread 持久化 (MySQL checkpoint)

```bash
# 发送消息并指定 thread_id
$ curl -s -X POST http://localhost:8100/ \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"message/send","params":{"message":{"role":"user","parts":[{"text":"My name is Alice"}]},"metadata":{"thread_id":"my-thread"}},"id":"1"}' | python3 -m json.tool

# 同一 thread_id 继续对话 — checkpoint 自动恢复上下文
$ curl -s -X POST http://localhost:8100/ \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"message/send","params":{"message":{"role":"user","parts":[{"text":"What is my name?"}]},"metadata":{"thread_id":"my-thread"}},"id":"2"}'

# 查询完整对话历史
$ curl -s http://localhost:8100/threads/my-thread | python3 -m json.tool
```

```json
{
    "thread_id": "my-thread",
    "messages": [
        {"role": "user", "content": "My name is Alice"},
        {"role": "assistant", "content": "Got it, Alice."},
        {"role": "user", "content": "What is my name?"},
        {"role": "assistant", "content": "Your name is Alice."}
    ]
}
```

```bash
# 列出所有 threads
$ curl -s http://localhost:8100/threads | python3 -m json.tool

# 删除 thread
$ curl -s -X DELETE http://localhost:8100/threads/my-thread
```

---

## 9. A2A JSON-RPC 方法

| 方法 | 说明 |
|------|------|
| `message/send` | 同步消息 (非流式) |
| `message/stream` | 流式消息 (SSE) |
| `tasks/get` | 查询任务 |
| `tasks/list` | 任务列表 |
| `tasks/cancel` | 取消任务 |
| `threads/list` | Thread 列表 (checkpoint) |
| `threads/get` | Thread 对话历史 |
| `threads/delete` | 删除 Thread |
| `threads/create` | 创建新 Thread |

---

## 10. 完整配置示例

下例展示了所有可用配置字段的完整 AGENTS.md：

```yaml
---
# === 身份 (必填) ===
name: "Full Featured Agent"
vendorKey: "myorg"
agentKey: "my-agent"
version: "1.0.0"
slug: "myorg/my-agent"

# === 元数据 (必填) ===
description: "A full-featured agent with all configuration options"
author: "@myorg"
license: "MIT"
tags:
  - demo
  - production

# === Skills ===
skills:
  - name: "bash-tool"
    source: "local"
    version: "1.0.0"
    required: true

# === MCP 服务器 ===
mcpServers:
  - vendor: "block"
    server: "filesystem"
    version: "1.0.0"
    configDir: "mcp-configs/filesystem"
    required: false

# === 子 Agent ===
agents:
  - vendor: "myorg"
    agent: "code-reviewer"
    version: "1.0.0"
    role: "reviewer"
    delegations:
      - code-quality
      - security-check
    required: false
    endpoint: "http://reviewer:8000"

# === 工具 ===
tools:
  - Read
  - Bash
  - Edit
  - Grep

# === 运行时配置 ===
config:
  temperature: 0.7
  max_tokens: 4096
  require_confirmation: false

# === 模型 ===
model:
  provider: "ctyun"
  name: "${LLM_MODEL_ID}"
  embedding: "text-embedding-v1"

# === 内存 ===
memory:
  type: "editable"
  blocks:
    personality: "default"
    user_context: "default"

# === 编排 ===
orchestration:
  entrypoint: "main"
  fallback: "error-handler"
  triggers:
    - event: "code-change"
      action: "review"

# === Harness 配置 ===
harnessConfig:
  deep-agents:
    a2a:
      protocol: "1.0.0"
      bindings:
        - jsonrpc
        - rest
      streaming: true
    a2ui:
      enabled: true
      version: "v0.8"
      catalog_id: "https://a2ui.org/specification/v0_8/standard_catalog_definition.json"
---

# Agent Purpose
You are a full-featured agent for demonstration.

## Core Responsibilities
- Answer questions
- Execute commands
- Use skills and MCP tools
```

---

## 11. 常见问题

### Q: 服务启动后 LLM 返回错误

检查日志中的 LLM 配置警告:

```
WARNING: LLM_API_KEY is not set
WARNING: LLM_MODEL_ID is not set
WARNING: LLM_BASE_URL is not set
```

确保环境变量已设置。模型必须以 OpenAI 兼容 API 提供。

### Q: 工具调用不生效

确认 `AGENTS.md` 中 `tools` 字段包含所需工具名 (`Bash`, `Read`, `Edit`, `Grep`)。

### Q: 端口被占用

修改 `SERVER_PORT` 环境变量，同时更新 `docker-compose.yml` 中的端口映射。

### Q: GLM-5 / 其他 reasoning 模型流式输出不工作

框架已内置 `ChatOpenAIReasoning` 适配层，自动将 `reasoning_content` 转换为 `content`，无需额外配置。

### Q: MCP 客户端连接失败

确认 `langchain-mcp-adapters` 已安装 (`pip install langchain-mcp-adapters`)。Docker 镜像已内置此依赖。

### Q: gunicorn 启动失败

确认 `wsgi.py` 存在 (`server/wsgi.py`)，gunicorn 使用 `server.wsgi:app` 作为入口。本地开发测试建议用 uvicorn 直接启动。

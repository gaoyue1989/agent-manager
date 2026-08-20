# Agent Framework — API 文档

**版本:** v2.1.0 (Java)

---

## REST 端点

### GET /

服务信息 + 协议声明。

```bash
curl http://localhost:8100/
```

**响应:**

```json
{
    "agent": "test-agent",
    "description": "A test agent",
    "version": "1.0.0",
    "protocols": {
        "a2a": "1.0.0",
        "a2ui": "v0.8",
        "oaf": "v0.8.0"
    },
    "oaf": {
        "tools": ["Read", "Bash", "Edit"],
        "skills": 1,
        "mcp": 1,
        "sub_agents": 0
    },
    "endpoints": {
        "agent_card": "/.well-known/agent-card.json",
        "jsonrpc": "/",
        "threads": "/threads",
        "health": "/health",
        "debug": "/debug",
        "chat_stream": "/chat/stream"
    },
    "engine": "AgentScope Java 2.0"
}
```

---

### GET /health

健康检查。

```bash
curl http://localhost:8100/health
```

**响应:**

```json
{
    "status": "healthy",
    "agent": "test-agent",
    "slug": "acme-test-agent",
    "llm_configured": true,
    "engine": "AgentScope Java 2.0",
    "version": "1.0.0",
    "tenant_prefix": "acme-test-agent"
}
```

---

### GET /.well-known/agent-card.json

A2A Agent Card 发现端点。

```bash
curl http://localhost:8100/.well-known/agent-card.json
```

**响应:**

```json
{
    "name": "test-agent",
    "description": "A test agent",
    "url": "http://localhost:8100",
    "version": "1.0.0",
    "provider": { "organization": "acme" },
    "capabilities": {
        "streaming": true,
        "stateTransitionHistory": true,
        "pushNotifications": false
    },
    "defaultInputModes": ["text/plain"],
    "defaultOutputModes": ["text/plain", "a2ui/v0.8"],
    "interfaces": [
        { "protocol": "JSONRPC", "url": "/" }
    ],
    "skills": [...],
    "securitySchemes": {
        "bearer": {
            "type": "http",
            "scheme": "bearer",
            "bearerFormat": "JWT"
        }
    }
}
```

---

### GET /skills

技能列表。

```bash
curl http://localhost:8100/skills
```

**响应:**

```json
[
    {
        "name": "code-review",
        "description": "Code review skill",
        "version": "1.0.0"
    }
]
```

---

### GET /mcp

MCP 服务器列表。

```bash
curl http://localhost:8100/mcp
```

**响应:**

```json
[
    {
        "server": "weather-service",
        "vendor": "weather",
        "connection_type": "sse",
        "url": "http://localhost:8811/sse",
        "tool_count": 3,
        "has_ui": true
    }
]
```

**字段说明：**

| 字段 | 说明 |
|------|------|
| `server` | MCP 服务器名 |
| `vendor` | 供应商 |
| `connection_type` | 传输类型（sse / streamableHttp / stdio） |
| `url` | 连接地址 |
| `tool_count` | **实际注册的工具数**（读 McpToolRegistrar 注册缓存；受 ActiveMCP.json `enabled: false` 过滤影响） |
| `has_ui` | **MCP Apps**：该 server 是否存在带 UI 元数据（`ui.tools.*.resource_uri`）的工具（供前端预检） |

---

### GET /tools

工具列表。

```bash
curl http://localhost:8100/tools
```

**响应:**

```json
[
    {
        "name": "get_current_time",
        "server": "builtin",
        "category": "builtin",
        "description": "返回指定时区当前时间"
    },
    {
        "name": "get_time",
        "server": "devmcp",
        "category": "mcp",
        "description": "获取指定时区当前时间",
        "uiResourceUri": "ui://get-time/mcp-app.html",
        "appOnly": true
    }
]
```

**字段说明：**

| 字段 | 说明 |
|------|------|
| `name` | 工具名（MCP 工具为远端裸名） |
| `server` | 工具来源（builtin / mcp server 名） |
| `category` | `builtin` / `mcp` |
| `description` | 工具描述 |
| `uiResourceUri` | **MCP Apps**（可选）：工具绑定的 `ui://` 资源 URI，由 config.yaml `ui.tools.*.resource_uri` 静态声明或 Manifest `_meta` 动态发现 |
| `appOnly` | **MCP Apps**（可选，默认 false）：`ui.app_only: true` 的工具仅作为卡片展示、不进入 LLM 工具集 |

---

### GET /mcp/{server}/resources/ui

**MCP Apps**：读取工具 UI 资源 HTML（经 CSP 元数据注入返回，供前端沙箱 iframe 使用）。

```bash
curl "http://localhost:8100/mcp/devmcp/resources/ui?uri=ui://get-time/mcp-app.html"
```

**响应:**

```json
{
    "html": "<!DOCTYPE html>...",
    "mimeType": "text/html",
    "csp": {
        "default": "default-src 'none'; script-src 'self' 'unsafe-inline'"
    }
}
```

**字段说明：**

| 字段 | 说明 |
|------|------|
| `html` | 资源 HTML 内容 |
| `mimeType` | MIME 类型 |
| `csp` | Content-Security-Policy（前端注入 `<meta http-equiv="Content-Security-Policy">`，srcdoc 下响应头 CSP 不生效） |

---

### GET /mcp/{server}/resources

**MCP Apps**：列出该 server 全部已声明/发现的 `ui://` 资源（供前端预拉取）。

```bash
curl http://localhost:8100/mcp/devmcp/resources
```

**响应:**

```json
{
    "server": "devmcp",
    "resources": ["ui://get-time/mcp-app.html"]
}
```

---

### POST /mcp/{server}/tools/{tool}

**MCP Apps**：UI 卡片代发工具调用（host → 后端 → MCP 工具）。

```bash
curl -X POST http://localhost:8100/mcp/devmcp/tools/get_time \
  -H "Content-Type: application/json" \
  -d '{"arguments": {"timezone": "Asia/Shanghai"}}'
```

**响应（成功）:**

```json
{
    "content": [{"type": "text", "text": "2026-08-20 15:38:00 CST"}],
    "isError": false,
    "structuredContent": {}
}
```

**响应（ask 工具未确认，403）:**

```json
{
    "needsConfirm": true,
    "toolCalls": [
        {"tool_call_id": "uuid", "name": "get_time", "input": {"timezone": "Asia/Shanghai"}}
    ]
}
```

前端弹 HITL 确认卡片，Approve 后带 `confirmed: true` 重试：

```bash
curl -X POST http://localhost:8100/mcp/devmcp/tools/get_time \
  -H "Content-Type: application/json" \
  -d '{"arguments": {"timezone": "Asia/Shanghai"}, "confirmed": true}'
```

**字段说明：**

| 字段 | 说明 |
|------|------|
| `arguments` | 工具参数对象（必填） |
| `confirmed` | ask 确认流：false/缺省首次调用，403 + needsConfirm；true 为确认后重试 |

---

### POST /mcp/ui-context

**MCP Apps (4.7)**：静默更新模型上下文（对应 App 的 `ui/update-model-context` 请求）。持久化到 `ui_context` 表，下次 agent 调用时经 UiContextInjectionHook 注入为 system context；**不触发新回复、不影响当前流**。

```bash
curl -X POST http://localhost:8100/mcp/ui-context \
  -H "Content-Type: application/json" \
  -d '{"sessionId": "acme-test-agent:thread-1", "content": "user toggled 24h clock format"}'
```

**响应:**

```json
{
    "updated": true,
    "sessionId": "acme-test-agent:thread-1"
}
```

**请求体说明：**

| 字段 | 说明 |
|------|------|
| `sessionId` | 会话 key（`tenant:thread` 格式，必填，校验防跨租户） |
| `content` | 文本上下文（与 structuredContent 至少一个） |
| `structuredContent` | 结构化上下文（可选） |

---

### GET /system-prompt

系统提示词（含自动生成的 Skills/MCP/Memory 上下文）。

```bash
curl http://localhost:8100/system-prompt
```

**响应:**

```json
{
    "system_prompt": "# Test Agent\n...\n\n## Available Skills\n...\n\n## Memory Recall\n...",
    "base_prompt": "# Test Agent\n\nThis is a test agent..."
}
```

---

### GET /threads

Thread 列表。

```bash
curl http://localhost:8100/threads
```

**响应:** `[]`

---

### GET /debug

调试页面 HTML（对齐 agentscope 官方前端布局）。

```bash
curl http://localhost:8100/debug
```

**响应:** HTML 页面

---

## 长连接 SSE API（对齐官方 agentscope 模式）

### GET /debug/threads/{sessionId}/events

长连接 SSE 订阅端点。连接建立后立即推送 `connected` 初始帧，后续推送该 session 的实时事件。

```bash
curl -s -N "http://localhost:8100/debug/threads/debug-user:abc/events"
```

**响应 (SSE):**

```
event: connected
data: {}

data: {"type":"AGENT_START","id":"...","replyId":"..."}
data: {"type":"TEXT_BLOCK_DELTA","id":"...","delta":"Hello","replyId":"...","blockId":"..."}
data: {"type":"AGENT_END","id":"...","replyId":"..."}
event: ping
data: {}
```

**特性:**
- `connected` 初始帧：确保 fetch() 立即返回（否则 Spring SSE 会等首个数据才发响应头）
- 心跳 ping：每 15 秒，保活连接
- 事件词表与 `/chat/stream` 完全一致（通过 AgentEventSseSerializer 共用）
- 自动重连：客户端断开后可重新订阅

---

### POST /debug/threads/{sessionId}/chat

长连接触发端点（fire-and-forget）。发送消息后立即返回 202，事件通过已建立的 SSE 订阅回流。

```bash
curl -s -X POST "http://localhost:8100/debug/threads/debug-user:abc/chat" \
  -H 'Content-Type: application/json' \
  -d '{"message":"hello","userId":"alice"}'
```

**请求体:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `message` | String | ✓ | 用户消息 |
| `userId` | String | | 用户标识（默认 `debug-user`） |

**响应 (202 Accepted):**

```json
{"accepted":true,"sessionId":"debug-user:abc"}
```

**时序约束（前端）：** 先建立 SSE 订阅（`GET .../events`），等待 `connected` 帧到达（`fetch()` 返回），再 POST 触发。保证事件不丢失。

---

## Channel SSE API

### GET /chat/stream

Channel 流式对话端点。

```bash
curl -s -N "http://localhost:8100/chat/stream?message=hello&userId=alice"
```

**参数:**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `message` | String | ✓ | 用户消息 |
| `userId` | String | ✓ | 用户标识（自动创建独立 session） |
| `sessionId` | String | | 可选，指定 session（同一用户多个对话） |
| `subagentId` | String | | 可选，直接与子 Agent 对话 |

**响应 (SSE):**

```
data: {"type":"TEXT_BLOCK_DELTA","id":"evt-1","delta":"Hello"}
data: {"type":"TEXT_BLOCK_DELTA","id":"evt-2","delta":"! I"}
data: {"type":"TEXT_BLOCK_DELTA","id":"evt-3","delta":"'m"}
...
```

**SSE 事件类型:**

| type | 说明 |
|------|------|
| `TEXT_BLOCK_DELTA` | 文本 token（流式累加） |
| `TOOL_CALL_START` | 工具调用开始 |
| `TOOL_RESULT_END` | 工具返回结果 |
| `AGENT_END` | Agent 执行完成 |

**多轮对话示例:**

```bash
# 第一轮：指定 userId
curl -s -N "http://localhost:8100/chat/stream?message=记住：我喜欢红色&userId=alice"

# 第二轮：同一 userId，自动恢复会话
curl -s -N "http://localhost:8100/chat/stream?message=我喜欢什么颜色？&userId=alice"
```

**子 Agent 对话示例:**

```bash
# 主 Agent 暴露子 Agent（通过 SubagentExposedEvent 获取 subagentId）
curl -s -N "http://localhost:8100/chat/stream?message=帮我做研究&userId=alice"

# 直接与子 Agent 对话
curl -s -N "http://localhost:8100/chat/stream?message=重点调查AI趋势&userId=alice&subagentId=<subagent-id>"
```

---

## A2A JSON-RPC

所有 A2A 请求发送到 `POST /`，Content-Type 为 `application/json`。

### JSON-RPC 请求格式

```json
{
    "jsonrpc": "2.0",
    "method": "<method_name>",
    "params": { ... },
    "id": "<request_id>"
}
```

### JSON-RPC 响应格式

**成功:**

```json
{
    "jsonrpc": "2.0",
    "id": "<request_id>",
    "result": { ... }
}
```

**失败:**

```json
{
    "jsonrpc": "2.0",
    "id": "<request_id>",
    "error": {
        "code": -32603,
        "message": "Error description"
    }
}
```

---

### message/send

发送同步消息，返回完整 Agent 响应。

**请求:**

```json
{
    "jsonrpc": "2.0",
    "method": "message/send",
    "params": {
        "message": {
            "role": "user",
            "parts": [
                { "kind": "text", "text": "请只回复 welcome" }
            ],
            "messageId": "msg-001",
            "kind": "message"
        }
    },
    "id": "1"
}
```

**参数说明:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `params.message.role` | string | ✓ | `"user"` 或 `"agent"` |
| `params.message.parts` | array | ✓ | Part 数组 |
| `params.message.parts[].kind` | string | | `"text"`（不填默认为 text）|
| `params.message.parts[].text` | string | ✓ | 文本内容 |
| `params.message.messageId` | string | | 消息 ID（不填自动生成）|
| `params.message.kind` | string | | `"message"`（不填默认为 message）|

> **简写格式**: 支持 `{"text": "hello"}`（不带 `kind` 字段），自动视为 text part。

**响应**（SDK 标准 A2A Message，`blocking: true` 同步返回）:

```json
{
    "jsonrpc": "2.0",
    "id": "1",
    "result": {
        "role": "agent",
        "parts": [
            {"kind": "text", "text": "welcome",
             "metadata": {"_agentscope_block_type": "text"}}
        ],
        "messageId": "041a0188-...",
        "contextId": "6bf711b8-...",
        "taskId": "c90c456b-...",
        "kind": "message"
    }
}
```

> **说明**: message/send 由 AgentScopeA2aServer（SDK）处理，返回标准 A2A `Message`（kind=message）。
> parts 可能包含 thinking 块（`metadata._agentscope_block_type = "thinking"`）与 text 块。
> 会话历史自动持久化到 agent_state 表（session_id 格式 `{userId}:{sessionId}`），可用 `tasks/get` 查询。

---

### message/stream

发送消息并通过 SSE 流式接收 Agent 响应。

**请求:**

```json
{
    "jsonrpc": "2.0",
    "method": "message/stream",
    "params": {
        "message": {
            "role": "user",
            "parts": [
                { "text": "请只回复 welcome" }
            ]
        }
    },
    "id": "s1"
}
```

**响应 (SSE，SDK 标准 A2A streaming 事件):**

```
id: s1
event: jsonrpc
data: {"jsonrpc":"2.0","id":"s1","result":{"id":"...","status":{"state":"submitted"},"kind":"task"}}

id: s1
event: jsonrpc
data: {"jsonrpc":"2.0","id":"s1","result":{"taskId":"...","status":{"state":"working"},"final":false,"kind":"status-update"}}

id: s1
event: jsonrpc
data: {"jsonrpc":"2.0","id":"s1","result":{"taskId":"...","artifact":{"parts":[{"text":"wel","metadata":{"_agentscope_block_type":"text"},"kind":"text"}]},"append":true,"lastChunk":false,"kind":"artifact-update"}}

id: s1
event: jsonrpc
data: {"jsonrpc":"2.0","id":"s1","result":{"taskId":"...","status":{"state":"completed"},"final":true,"kind":"status-update"}}
```

**SSE 事件类型（result.kind）:**

| kind | 说明 |
|------|------|
| `task` | 任务创建（state: submitted） |
| `status-update` | 任务状态变更（working/completed/...，`final:true` 表示结束） |
| `artifact-update` | 流式产物增量（parts 带 `_agentscope_block_type`: thinking/text） |
| `message` | 最终完整消息（含 token 用量 metadata） |

---

### tasks/get

查询任务状态。

**请求:**

```json
{
    "jsonrpc": "2.0",
    "method": "tasks/get",
    "params": { "id": "<task-id>" },
    "id": "2"
}
```

**响应**（SDK 标准 A2A Task，含 history 消息列表）:

```json
{
    "jsonrpc": "2.0",
    "id": "2",
    "result": {
        "id": "<session-id>",
        "contextId": "<session-id>",
        "status": { "state": "completed", "timestamp": "2026-08-10T10:13:44Z" },
        "artifacts": [],
        "history": [
            {"role": "user", "parts": [{"text": "hello", "kind": "text"}],
             "messageId": "...", "kind": "message"},
            {"role": "agent", "parts": [{"text": "hi", "kind": "text"}],
             "messageId": "...", "kind": "message"}
        ],
        "metadata": {},
        "kind": "task"
    }
}
```

**参数说明:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `params.id` | string | ✓ | session_id（agent_state 表 session_id，如 `debug-user:a2a-sdk-test-1`） |
| `params.historyLength` | int | | 返回消息数上限（-1/缺省=全部，0=仅元信息，N=最后 N 条） |

**错误:** 任务不存在时返回 `error.code = -32001 (Task not found)`。

---

### tasks/list

> **注**: A2A SDK (0.3.3) 不提供 `tasks/list` 方法，调用将返回 `-32601 Method not found`。
> 会话列表可通过 `GET /debug/threads` 获取。

---

### tasks/cancel

取消任务。

**请求:**

```json
{
    "jsonrpc": "2.0",
    "method": "tasks/cancel",
    "params": { "id": "<task-id>" },
    "id": "4"
}
```

---

### 错误码

| code | 说明 |
|------|------|
| -32600 | Invalid Request |
| -32601 | Method not found |
| -32602 | Invalid params |
| -32603 | Internal error |

---

## Part 格式说明

### 支持的 Part 类型

| kind | 字段 | 说明 |
|------|------|------|
| `text` | `text`: string | 文本内容 |
| `file` | 文件名 + 数据 | 文件 Part |
| `data` | 结构化数据 | 数据 Part |

### 向后兼容

Controller 兼容无 `kind` 字段的简写格式：

```json
// 完整格式（推荐）
{"kind": "text", "text": "hello"}

// 简写格式（兼容 debug 页面）
{"text": "hello"}
```

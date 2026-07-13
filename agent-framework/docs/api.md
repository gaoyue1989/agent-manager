# Agent Framework — API 文档

**版本:** v2.0.0 (Java)

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
    "description": "A test agent for OAF config loader tests",
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
        "debug": "/debug"
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
    "description": "A test agent for OAF config loader tests",
    "url": "",
    "version": "1.0.0",
    "provider": { "organization": "acme" },
    "capabilities": {
        "streaming": true,
        "stateTransitionHistory": true,
        "pushNotifications": false
    },
    "defaultInputModes": ["text", "text/plain"],
    "defaultOutputModes": ["text", "text/plain", "a2ui/v0.8"],
    "skills": [...],
    "extensions": [...],
    "securitySchemes": { "bearer": {...} }
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
        "name": "bash-tool",
        "description": "Execute bash commands",
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
        "connection_type": "N/A",
        "url": "N/A",
        "tool_count": 0
    }
]
```

---

### GET /tools

工具列表。

```bash
curl http://localhost:8100/tools
```

---

### GET /system-prompt

系统提示词（含自动生成的 Skills/MCP 上下文）。

```bash
curl http://localhost:8100/system-prompt
```

**响应:**

```json
{
    "system_prompt": "# Test Agent\n\n...\n\n## Available Skills\n...\n\n## Available MCP Servers\n...",
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

调试页面 HTML。

```bash
curl http://localhost:8100/debug
```

**响应:** HTML 页面 (约 36KB)

---

### POST /chat/stream

SSE 流式对话。

```bash
curl -s -N -X POST http://localhost:8100/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message":"hello","metadata":{}}'
```

**响应 (SSE):**

```
data: {"type":"task_update","state":"working","id":"<uuid>"}
data: {"type":"token","token":"Hello","task_id":"<uuid>"}
data: {"type":"token","token":"! I","task_id":"<uuid>"}
...
data: {"type":"done"}
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
                { "kind": "text", "text": "say hi" }
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

**响应:**

```json
{
    "jsonrpc": "2.0",
    "id": "1",
    "result": {
        "id": "<task-uuid>",
        "status": "completed",
        "result": {
            "message": {
                "kind": "message",
                "role": "agent",
                "messageId": "<msg-uuid>",
                "parts": [
                    { "kind": "text", "text": "Hi! I'm the test agent..." }
                ]
            }
        }
    }
}
```

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
                { "text": "hello" }
            ]
        }
    },
    "id": "s1"
}
```

**响应 (SSE):**

```
data: {"type":"task_update","state":"working","id":"<uuid>"}
data: {"type":"token","token":"Hello","task_id":"<uuid>"}
data: {"type":"token","token":"! I","task_id":"<uuid>"}
data: {"type":"token","token":"'m","task_id":"<uuid>"}
...
data: {"type":"task_update","state":"completed","id":"<uuid>","metadata":{"thread_id":"<uuid>"}}
data: {"type":"done"}
data: [DONE]
```

**SSE 事件类型:**

| data.type | 说明 |
|-----------|------|
| `task_update` | 任务状态变更 (working/completed) |
| `token` | 文本 token（流式累加） |
| `tool_call` | 工具调用 |
| `tool_result` | 工具返回结果 |
| `error` | 错误信息 |
| `done` | 完成信号 |
| `[DONE]` | SSE 流结束标记 |

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
| `file` | 文件名 + 数据 | 文件 Part（预留） |
| `data` | 结构化数据 | 数据 Part（预留） |

### 向后兼容

Controller 兼容无 `kind` 字段的简写格式：

```json
// 完整格式（推荐）
{"kind": "text", "text": "hello"}

// 简写格式（兼容 debug 页面）
{"text": "hello"}
```

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
        "tool_count": 3
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

---

### GET /tools

工具列表。

```bash
curl http://localhost:8100/tools
```

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

调试页面 HTML。

```bash
curl http://localhost:8100/debug
```

**响应:** HTML 页面

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

**响应:**

```json
{
    "jsonrpc": "2.0",
    "id": "1",
    "result": {
        "id": "<task-uuid>",
        "status": {
            "state": "TASK_STATE_COMPLETED",
            "timestamp": "2026-08-06T00:00:00Z",
            "message": {
                "role": "agent",
                "parts": [{"type": "text", "text": "welcome"}]
            }
        },
        "artifacts": [],
        "metadata": {}
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
                { "text": "请只回复 welcome" }
            ]
        }
    },
    "id": "s1"
}
```

**响应 (SSE):**

```
data: {"jsonrpc":"2.0","method":"tasks/statusUpdate","params":{"id":"...","status":{"state":"TASK_STATE_WORKING"}}}
data: {"jsonrpc":"2.0","method":"tasks/artifactUpdate","params":{"id":"...","artifact":{"parts":[{"type":"text","text":"welcome"}]}}}
data: {"jsonrpc":"2.0","method":"tasks/statusUpdate","params":{"id":"...","status":{"state":"TASK_STATE_COMPLETED"}}}
```

**SSE 事件类型:**

| method | 说明 |
|--------|------|
| `tasks/statusUpdate` | 任务状态变更 (WORKING/COMPLETED/FAILED/CANCELED) |
| `tasks/artifactUpdate` | 任务产物更新（文本/文件/结构化数据） |

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

**响应:**

```json
{
    "jsonrpc": "2.0",
    "id": "2",
    "result": {
        "id": "<task-id>",
        "status": { "state": "TASK_STATE_COMPLETED" },
        "artifacts": [...]
    }
}
```

---

### tasks/list

列出任务。

**请求:**

```json
{
    "jsonrpc": "2.0",
    "method": "tasks/list",
    "params": {},
    "id": "3"
}
```

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

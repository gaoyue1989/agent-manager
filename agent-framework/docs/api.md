# Agent Framework — API 文档

**版本:** v2.1.0 (Java) — 无状态单次流架构

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

---

### GET /skills

技能列表。

```bash
curl http://localhost:8100/skills
```

---

### GET /mcp

MCP 服务器列表。

```bash
curl http://localhost:8100/mcp
```

**字段说明：**

| 字段 | 说明 |
|------|------|
| `server` | MCP 服务器名 |
| `vendor` | 供应商 |
| `connection_type` | 传输类型（sse / streamableHttp / stdio） |
| `url` | 连接地址 |
| `tool_count` | 实际注册的工具数（受 ActiveMCP.json `enabled: false` 过滤影响） |
| `has_ui` | MCP Apps：该 server 是否存在带 UI 元数据的工具 |

---

### GET /tools

工具列表。

```bash
curl http://localhost:8100/tools
```

**字段说明：**

| 字段 | 说明 |
|------|------|
| `name` | 工具名（MCP 工具为远端裸名） |
| `server` | 工具来源（builtin / mcp server 名） |
| `category` | `builtin` / `mcp` |
| `description` | 工具描述 |
| `uiResourceUri` | MCP Apps（可选）：工具绑定的 `ui://` 资源 URI |
| `appOnly` | MCP Apps（可选，默认 false）：`ui.app_only: true` 的工具仅卡片展示 |

---

### GET /mcp/{server}/resources/ui

MCP Apps：读取工具 UI 资源 HTML（经 CSP 元数据注入返回）。

---

### GET /mcp/{server}/resources

MCP Apps：列出该 server 全部 `ui://` 资源。

---

### POST /mcp/{server}/tools/{tool}

MCP Apps：UI 卡片代发工具调用。

---

### POST /mcp/ui-context

MCP Apps (4.7)：静默更新模型上下文。

---

### GET /system-prompt

系统提示词。

---

## 会话 API（O7：统一迁 /threads）

### GET /threads

Thread 列表（agent_state 表 session_id 去重）。

```bash
curl http://localhost:8100/threads
```

**响应:**

```json
[
    {
        "session_id": "acme-test-agent:thread-1",
        "thread_id": "thread-1",
        "updated_at": "2026-08-21T10:00:00Z"
    }
]
```

---

### GET /threads/{sessionId}/history

Thread 历史消息 + pendingConfirm（state_data 尽力解析，供刷新重建确认卡片）。

```bash
curl http://localhost:8100/threads/acme-test-agent:thread-1/history
```

**响应:**

```json
{
    "session_id": "acme-test-agent:thread-1",
    "pendingConfirm": {
        "reply_id": "reply-001",
        "tools": [
            {"tool_call_id": "uuid", "name": "submit_application", "input": {"action": "submit"}}
        ],
        "created_at": "2026-08-21T10:05:00Z"
    },
    "messages": [
        {"role": "user", "content": "提交申请"},
        {"role": "agent", "content": "请确认是否提交？", "tool_calls": [...]}
    ]
}
```

---

### GET /threads/{sessionId}/llm-calls

LLM 调用记录。

```bash
curl http://localhost:8100/threads/acme-test-agent:thread-1/llm-calls
```

---

## 无状态单次流 SSE API

### POST /threads/{sessionId}/chat

单次流 SSE 对话端点。每次请求抢 Turn 租约（排队语义）→ Agent 执行 → 事件直吐 → AGENT_END/error 帧关闭流、释放租约。**无长连接、无 SessionEventBus**。

```bash
curl -s -N -X POST "http://localhost:8100/threads/acme-test-agent:thread-1/chat" \
  -H 'Content-Type: application/json' \
  -d '{"message":"hello","userId":"alice"}'
```

**请求体:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `message` | String | ✓ | 用户消息 |
| `userId` | String | | 用户标识（默认 `debug-user`） |

**响应 (SSE):**

```
data: {"type":"waiting"}                    ← 排队等待时每 15s 一帧（防 Nginx 读超时）
data: {"type":"TEXT_BLOCK_DELTA","delta":"Hello","replyId":"...","blockId":"..."}
data: {"type":"AGENT_END","replyId":"..."}
```

**SSE 事件类型:**

| type | 说明 |
|------|------|
| `waiting` | 排队等待（同 session 有活跃 turn 时） |
| `TEXT_BLOCK_DELTA` | 文本 token（流式累加） |
| `TOOL_CALL_START` | 工具调用开始（MCP Apps 工具携带 `ui` 元数据） |
| `TOOL_RESULT_END` | 工具返回结果 |
| `permission_ask` | HITL 暂停点（需人工确认） |
| `AGENT_END` | Agent 执行完成（流关闭） |
| `done` | 流完成 |
| `error` | 错误（如 `turn_in_progress` 排队超时） |

**时序约束：**
- 单次 POST 即发起完整执行段，无需先建立 SSE 订阅
- 同 session 并发请求自动排队（Turn 租约），排队超时 120s 返回 error 帧
- HITL 暂停点：上下文落库 `confirm_context`，释放 Turn 租约，流关闭；恢复走 `confirm-stream`

---

### POST /threads/{sessionId}/confirm

HITL 确认同步端点。携带确认决策恢复 agent 执行，同步返回最终回复。

```bash
curl -X POST "http://localhost:8100/threads/acme-test-agent:thread-1/confirm" \
  -H 'Content-Type: application/json' \
  -d '{"results":[{"tool_call_id":"uuid","confirmed":true,"accept_rule":false}]}'
```

**错误码:**

| 状态码 | error | 说明 |
|--------|-------|------|
| 404 | `confirm_context_not_found` | 会话不存在或确认上下文已过期 |
| 409 | `confirm_already_consumed` | 重复确认（CAS 防护） |

---

### POST /threads/{sessionId}/confirm-stream

HITL 确认流式端点。确认后恢复执行，事件通过 SSE 流式下发（新执行段，需重新 acquire Turn 租约）。

```bash
curl -s -N -X POST "http://localhost:8100/threads/acme-test-agent:thread-1/confirm-stream" \
  -H 'Content-Type: application/json' \
  -d '{"results":[{"tool_call_id":"uuid","confirmed":true,"accept_rule":false}]}'
```

**错误帧（预检/租约失败时）:**

```
data: {"type":"error","error":"confirm_context_not_found: ..."}
data: {"type":"error","error":"turn_in_progress: session '...' has an active turn"}
```

---

## Channel SSE API

### GET /chat/stream

Channel 流式对话端点（传统 Channel 模式，与单次流 API 并存）。

```bash
curl -s -N "http://localhost:8100/chat/stream?message=hello&userId=alice"
```

---

## A2A JSON-RPC

所有 A2A 请求发送到 `POST /`。

### message/send

发送同步消息，返回完整 Agent 响应。

### message/stream

发送消息并通过 SSE 流式接收 Agent 响应。

### tasks/get

查询任务状态。

### tasks/cancel

取消任务。

---

## 数据库表（无状态单次流架构）

无状态单次流架构引入三张新表，服务启动时自动建表（幂等）。

### confirm_context

HITL 确认上下文（人工确认场景跨副本持久化）。Session 粒度覆盖写，CAS 防重复确认。

| 列 | 类型 | 说明 |
|----|------|------|
| `session_id` | VARCHAR(255) PK | 会话 key |
| `tool_calls_json` | MEDIUMTEXT | 待确认工具调用列表（`[{id, name, input}]`） |
| `reply_id` | VARCHAR(64) | 触发确认的 reply 标识 |
| `runtime_session_id` | VARCHAR(255) | Channel 流程网关推导的真实 sessionId |
| `runtime_user_id` | VARCHAR(255) | Channel 流程网关推导的真实 userId |
| `created_at` | DATETIME(3) | 创建时间（TTL 懒判断依据） |
| `consumed` | TINYINT(1) | 0=待确认，1=已消费（CAS 0→1 防重复） |

**TTL:** 默认 30 分钟（`confirmTtlMinutes`），读时懒判断 + 定时清理兜底。

### turn_lease

Turn 租约（同一 session 执行段串行化）。Token + 短 TTL + 续租，崩溃由 TTL 过期兜底。

| 列 | 类型 | 说明 |
|----|------|------|
| `session_id` | VARCHAR(255) PK | 会话 key |
| `token` | CHAR(36) | 租约 token（UUID，release/renew 时校验防误删） |
| `expires_at` | DATETIME(3) | 过期时间 |
| `created_at` | DATETIME(3) | 创建时间 |

**TTL:** 默认 60 秒（`turnLeaseTtlSeconds`），续租间隔默认 20 秒（`turnLeaseRenewSeconds`）。

**租约语义：** 只覆盖活跃执行段。permission_ask（HITL 暂停点）即让出锁；confirm-stream 恢复 = 新执行段需重新 acquire。

### tool_audit_log

工具调用审计日志（仅元信息：何时/何工具/何状态，不落参数）。异步批量写入，失败静默降级。

| 列 | 类型 | 说明 |
|----|------|------|
| `id` | BIGINT PK | 自增 ID |
| `session_id` | VARCHAR(255) | 会话 key |
| `tool_name` | VARCHAR(255) | 工具名 |
| `tool_call_id` | VARCHAR(64) | 工具调用 ID |
| `state` | VARCHAR(32) | 事件类型 |
| `payload_json` | MEDIUMTEXT | 完整事件 JSON payload |
| `created_at` | DATETIME(3) | 创建时间 |

**保留期:** 默认 30 天（`auditRetentionDays`），日级定时清理。

---

## 清理配置

无状态单次流架构清理参数通过 `CleanupConfig`（环境变量前缀 `AGENT_CLEANUP_*`）配置：

| 环境变量 | 默认值 | 说明 |
|---------|--------|------|
| `AGENT_CLEANUP_CONFIRM_TTL_MINUTES` | `30` | confirm_context 有效时长（分钟） |
| `AGENT_CLEANUP_TURN_LEASE_TTL_SECONDS` | `60` | turn_lease 租约 TTL（秒） |
| `AGENT_CLEANUP_TURN_LEASE_RENEW_SECONDS` | `20` | turn 续租间隔（秒） |
| `AGENT_CLEANUP_AUDIT_RETENTION_DAYS` | `30` | tool_audit_log 保留天数 |
| `AGENT_CLEANUP_SESSION_RETENTION_DAYS` | `7` | agent_state/agent_fs 保留天数 |

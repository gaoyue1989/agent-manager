# Agent Framework — 前端对接接口文档（SSE 模式）

**版本:** v2.3.0 | **架构:** 无状态单次流 SSE（Durable SSE）  
**Base URL:** `http://{host}:8100`  
**Content-Type:** 请求 `application/json`；SSE 响应 `text/event-stream`

> **通用请求头：** 所有接口均支持 `X-User-Id` 请求头传递用户标识（网关注入优先，
> 缺省回落请求参数中的 `userId`，再缺省 `debug-user`）。

---

## 目录

1. [概述与核心概念](#1-概述与核心概念)
2. [SSE 对话接口](#2-sse-对话接口)
   - [POST /threads/chat](#21-post-threadschat)
   - [GET /threads/{sessionId}/subscribe](#22-get-threadssessionidsubscribe)
   - [GET /threads/{sessionId}/status](#23-get-threadssessionidstatus)
3. [HITL 人工确认接口](#3-hitl-人工确认接口)
   - [POST /threads/{sessionId}/confirm](#31-post-threadssessionidconfirm)
   - [POST /threads/{sessionId}/confirm-stream](#32-post-threadssessionidconfirm-stream)
4. [会话管理接口](#4-会话管理接口)
   - [GET /threads](#41-get-threads)
   - [GET /threads/{sessionId}](#42-get-threadssessionid)
   - [GET /threads/{sessionId}/history](#43-get-threadssessionidhistory)
   - [PATCH /threads/{sessionId}](#44-patch-threadssessionid)
   - [DELETE /threads/{sessionId}](#45-delete-threadssessionid)
   - [GET /threads/{sessionId}/llm-calls](#46-get-threadssessionidllm-calls)
5. [文件上传下载接口](#5-文件上传下载接口)
   - [POST /files/upload](#51-post-filesupload)
   - [GET /files/{fileId}](#52-get-filesfileid)
6. [Agent 信息接口](#6-agent-信息接口)
7. [工具与 MCP 接口](#7-工具与-mcp-接口)
8. [Skill 管理与 @引用接口](#8-skill-管理与引用接口)
9. [SSE 事件词表（完整）](#9-sse-事件词表完整)
9. [错误处理](#9-错误处理)
10. [前端对接指南](#10-前端对接指南)
11. [数据表参考](#11-数据表参考)

---

## 1. 概述与核心概念

### 1.1 Durable SSE 架构

所有对话走 **POST 请求 + SSE 单次流**，事件经 EventBus 持久化 + 广播，SSE 断连不影响 Agent 执行：

```
前端 ──POST /threads/chat (SSE)──▶ 服务端
       ├─ 抢 Turn 租约（排队时发 waiting 帧）
       ├─ Agent 执行 → 事件写入 EventBus → 持久化 + 广播 → SSE 订阅吐出
       └─ AGENT_END / error 帧关闭流
（SSE 断连后可通过 GET /subscribe 重连续传）
```

**核心特性：**
- **事件持久化**：所有 Agent 事件写入 `session_event` 表，SSE 断连后可通过 `GET /subscribe?afterSeq=N` 回放+续传
- **Turn 租约串行化**：同 session 并发请求自动排队（waiting 帧），超时 120s 返回 error
- **HITL 暂停**：`permission_ask` 时上下文落库、释放租约，确认后走 `confirm-stream` 恢复
- **心跳防超时**：EventBus 心跳流（默认 20s 间隔），防止 Nginx/CDN 读超时

### 1.2 Session ID 格式

```
{tenant_prefix}:{threadId}
```

示例：`acme-test-agent:thread-1`

### 1.3 SSE 数据格式

每帧格式：
```
data: {"type":"TEXT_BLOCK_DELTA","delta":"Hello","replyId":"xxx","blockId":"yyy","id":"evt-001"}
```

- 所有事件均为 JSON，`type` 字段标识事件类型
- 通用字段：`type`、`id`（事件 ID）、`replyId`（回复 ID）、`blockId`（块 ID）

---

## 2. SSE 对话接口

### 2.1 POST /threads/chat

**唯一对话端点**——sessionId 在请求体中，可选：

- 不传 `sessionId` 时自动生成 UUID，首个 SSE 事件为 `session_created`
- 传了 `sessionId` 则续接已有会话

**适用场景：** 前端首次调用无需预知 sessionId，适合「新建对话」流程。

```
POST /threads/chat
Content-Type: application/json
Accept: text/event-stream
```

**请求体：**

```json
{
  "message": "你好",
  "userId": "alice",
  "sessionId": "my-session-1",
  "fileIds": []
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `message` | String | ⚠️ | 用户消息内容（`message` 与 `fileIds` 至少填一项） |
| `userId` | String | ❌ | 用户标识，默认 `debug-user`（`X-User-Id` Header 优先） |
| `sessionId` | String | ❌ | 会话 ID（不传则自动生成 UUID） |
| `fileIds` | Array\<String\> | ⚠️ | 上传文件 ID 列表 |

**新会话 SSE 响应示例：**

```
data: {"type":"session_created","session_id":"auto-uuid-xxx"}
data: {"type":"AGENT_START","replyId":"r1","sessionId":"auto-uuid-xxx",...}
data: {"type":"TEXT_BLOCK_DELTA","delta":"你好！","replyId":"r1","blockId":"b1",...}
data: {"type":"AGENT_END","replyId":"r1",...}
```

**续接已有会话：**

```
→ POST /threads/chat  {"message":"继续","sessionId":"my-session-1","userId":"alice"}
← SSE 事件流（无 session_created 前缀）
```

> **注意：** 其余端点（subscribe / status / confirm / history）仍需 sessionId 在路径中，因为它们是面向已知会话的操作。

**cURL 示例：**

```bash
# 新建对话（自动生成 sessionId）
curl -s -N -X POST "http://localhost:8100/threads/chat" \
  -H 'Content-Type: application/json' \
  -d '{"message":"你好"}'

# 续接已有会话
curl -s -N -X POST "http://localhost:8100/threads/chat" \
  -H 'Content-Type: application/json' \
  -d '{"message":"继续","sessionId":"acme-test-agent:thread-1"}'
```

---

### 2.2 GET /threads/{sessionId}/subscribe

**SSE 重连续传端点**——前端 SSE 断连后调用此接口，从 `afterSeq` 之后回放历史 + 订阅实时事件。

```
GET /threads/{sessionId}/subscribe?afterSeq=42&replyId=r1
Accept: text/event-stream
```

**路径参数：**

| 参数 | 类型 | 说明 |
|------|------|------|
| `sessionId` | String | 会话 ID |

**查询参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `afterSeq` | Integer | ❌ | 回放游标（从该序号之后开始回放），默认 0 |
| `replyId` | String | ❌ | Turn 标识，仅回放/订阅指定 turn 的事件 |

**响应行为：**

- **Turn 进行中**：先回放 `afterSeq` 之后的历史事件（从 `session_event` 表），再订阅实时 EventBus 流
- **Turn 已完成**：回放历史事件后追加 `done` 帧并关闭（不订阅实时流）

**`done` 帧示例（仅已完成 turn 出现）：**

```
data: {"type":"done"}
```

> 前端应在每个 SSE 事件中记录 `seq`（序号），断连后用最后收到的 `seq` 作为 `afterSeq` 续传。

---

### 2.3 GET /threads/{sessionId}/status

**查询当前 Turn 状态**——前端刷新恢复时先调用此接口判断执行状态。

```
GET /threads/{sessionId}/status
```

**响应：**

```json
{
  "session_id": "acme-test-agent:thread-1",
  "state": "working",
  "latest_event_seq": 42,
  "reply_id": "r1",
  "pending_confirm": ""
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `state` | String | `working` / `completed` / `waiting_confirm` / `idle` |
| `latest_event_seq` | Integer | 最新事件序号（供 `afterSeq` 参数使用） |
| `reply_id` | String | 当前 turn 的回复 ID |
| `pending_confirm` | String/Object | 待确认上下文（`waiting_confirm` 时非空） |

**前端刷新恢复逻辑：**

```
1. GET /status → 判断 state
2. working / waiting_confirm → GET /subscribe?afterSeq=N 续传
3. completed → GET /subscribe?afterSeq=N 回放（收到 done 帧后关闭）
4. idle → 显示空白输入状态
```

---

## 3. HITL 人工确认接口

当 SSE 流中出现 `permission_ask` 事件时，Agent 暂停等待用户决策。
确认后通过以下接口恢复执行。

### 3.1 POST /threads/{sessionId}/confirm

**同步确认**——恢复 Agent 执行，阻塞返回最终回复。

```
POST /threads/{sessionId}/confirm
Content-Type: application/json
```

**请求体：**

```json
{
  "results": [
    {
      "tool_call_id": "call-abc",
      "confirmed": true,
      "accept_rule": false
    },
    {
      "tool_call_id": "call-def",
      "confirmed": false,
      "accept_rule": false
    }
  ]
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `results` | Array | ✅ | 确认结果列表 |
| `results[].tool_call_id` | String | ✅ | 工具调用 ID（对应 `permission_ask` 中的 `tool_call_id`） |
| `results[].confirmed` | Boolean | ✅ | `true`=批准执行，`false`=拒绝执行 |
| `results[].accept_rule` | Boolean | ❌ | 是否接受建议规则（后续同型调用自动放行），默认 `false` |

**成功响应（200）：**

```json
{
  "response": "文件已成功写入 /tmp/test.txt",
  "thread_id": "thread-1"
}
```

**错误响应：**

| 状态码 | error | 说明 |
|--------|-------|------|
| 404 | `confirm_context_not_found` | 会话不存在或确认上下文已过期 |
| 409 | `confirm_already_consumed` | 重复确认（CAS 防护） |

---

### 3.2 POST /threads/{sessionId}/confirm-stream

**流式确认**——恢复 Agent 执行，通过 SSE 流式下发恢复过程的所有事件。

```
POST /threads/{sessionId}/confirm-stream
Content-Type: application/json
Accept: text/event-stream
```

**请求体：** 同 [3.1 confirm](#31-post-threadssessionidconfirm)

**响应（SSE 事件流）：**

事件词表与普通对话流完全一致（`TEXT_BLOCK_DELTA`、`TOOL_CALL_START`、`AGENT_END` 等），
前端可复用同一渲染逻辑。

```
data: {"type":"AGENT_START","replyId":"reply-001",...,"id":"evt-100"}
data: {"type":"TOOL_RESULT_START","toolCallId":"call-abc","toolCallName":"write_file",...,"id":"evt-101"}
data: {"type":"TOOL_RESULT_TEXT_DELTA","delta":"File written successfully","toolCallId":"call-abc",...,"id":"evt-102"}
data: {"type":"TOOL_RESULT_END","state":"COMPLETE","toolCallId":"call-abc",...,"id":"evt-103"}
data: {"type":"TEXT_BLOCK_START","replyId":"reply-001","blockId":"blk-010",...,"id":"evt-104"}
data: {"type":"TEXT_BLOCK_DELTA","delta":"文件已写入","replyId":"reply-001","blockId":"blk-010",...,"id":"evt-105"}
data: {"type":"AGENT_END","replyId":"reply-001","id":"evt-106"}
```

**预检失败时的 error 帧：**

```
data: {"type":"error","error":"confirm_context_not_found: Session not found or confirm context expired"}
data: {"type":"error","error":"turn_in_progress: session '...' has an active turn"}
```

**cURL 示例：**

```bash
curl -s -N -X POST "http://localhost:8100/threads/acme-test-agent:thread-1/confirm-stream" \
  -H 'Content-Type: application/json' \
  -d '{"results":[{"tool_call_id":"call-abc","confirmed":true,"accept_rule":false}]}'
```

---

## 4. 会话管理接口

### 4.1 GET /threads

获取会话列表，支持按 `userId` 过滤。

```
GET /threads?userId=alice
```

**查询参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `userId` | String | ❌ | 用户标识过滤（`X-User-Id` Header 优先） |

**请求头：**

| Header | 说明 |
|--------|------|
| `X-User-Id` | 网关注入的用户标识，优先于查询参数 `userId` |

**响应：**

```json
[
  {
    "session_id": "acme-test-agent:thread-1",
    "thread_id": "thread-1",
    "user_id": "alice",
    "title": "报表分析",
    "updated_at": "2026-08-21T10:00:00"
  },
  {
    "session_id": "acme-test-agent:thread-2",
    "thread_id": "thread-2",
    "user_id": "alice",
    "title": "",
    "updated_at": "2026-08-20T15:30:00"
  }
]
```

---

### 4.2 GET /threads/{sessionId}

获取会话详情（元信息 + 历史消息 + 待确认 + 产出文件）。

```bash
curl http://localhost:8100/threads/acme-test-agent:thread-1
```

**响应：**

```json
{
  "session_id": "acme-test-agent:thread-1",
  "user_id": "alice",
  "updated_at": "2026-08-21T10:00:00",
  "pendingConfirm": {
    "reply_id": "reply-001",
    "tools": [
      {
        "tool_call_id": "call-abc",
        "name": "write_file",
        "input": {"path": "/tmp/test.txt", "content": "hello"}
      }
    ],
    "created_at": "2026-08-21T10:05:00"
  },
  "files": [
    {
      "file_id": "f1e2d3c4-b5a6-7890-abcd-ef1234567890",
      "file_name": "report.xlsx",
      "mime_type": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      "size": 12345
    }
  ],
  "messages": [
    {"role": "user", "content": "帮我写文件"},
    {"role": "agent", "content": "请确认是否写入？", "tool_calls": [...]}
  ]
}
```

---

### 4.3 GET /threads/{sessionId}/history

获取会话历史消息 + 待确认信息 + 产出文件（供页面刷新后重建 UI）。

```bash
curl http://localhost:8100/threads/acme-test-agent:thread-1/history
```

**响应：**

```json
{
  "session_id": "acme-test-agent:thread-1",
  "pendingConfirm": {
    "reply_id": "reply-001",
    "tools": [
      {
        "tool_call_id": "call-abc",
        "name": "write_file",
        "input": {"path": "/tmp/test.txt", "content": "hello"}
      }
    ],
    "created_at": "2026-08-21T10:05:00"
  },
  "files": [
    {
      "file_id": "f1e2d3c4-b5a6-7890-abcd-ef1234567890",
      "file_name": "report.xlsx",
      "mime_type": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      "size": 12345
    }
  ],
  "messages": [
    {"role": "user", "content": "帮我写文件"},
    {"role": "agent", "content": "请确认是否写入？", "tool_calls": [...]}
  ]
}
```

> `pendingConfirm` 仅为待确认状态时存在，无待确认时为 `null`。  
> `files` 为 Agent 产出的文件列表（origin=generated），前端据此渲染下载卡片。  
> 前端据此在刷新后重建确认卡片和产出文件卡片。

---

### 4.4 PATCH /threads/{sessionId}

重命名会话（title 写入 session_user.remark）。

```
PATCH /threads/{sessionId}
Content-Type: application/json
```

**请求体：**

```json
{
  "title": "报表分析"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `title` | String | ✅ | 新标题（不可为空） |

**响应：**

```json
{
  "session_id": "acme-test-agent:thread-1",
  "title": "报表分析"
}
```

---

### 4.5 DELETE /threads/{sessionId}

删除会话及其所有关联数据（级联清理）。

```bash
curl -X DELETE http://localhost:8100/threads/acme-test-agent:thread-1
```

**响应：**

```json
{
  "session_id": "acme-test-agent:thread-1",
  "deleted": true,
  "rows_affected": 15
}
```

> 级联清理范围：agent_state、agent_fs、session_event、session_user、confirm_context、turn_lease、file_asset（generated 文件）。

---

### 4.6 GET /threads/{sessionId}/llm-calls

获取 LLM 调用记录。

```bash
curl http://localhost:8100/threads/acme-test-agent:thread-1/llm-calls
```

**响应：**

```json
{
  "session_id": "acme-test-agent:thread-1",
  "calls": [
    {
      "call_id": "call-001",
      "timestamp": "2026-08-21T10:00:05",
      "request": "...",
      "response": "..."
    }
  ]
}
```

---

## 5. 文件上传下载接口

### 5.1 POST /files/upload

**文件上传**——multipart 表单上传，返回 `file_id` 供对话接口引用。

```
POST /files/upload
Content-Type: multipart/form-data
```

**表单参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `file` | File | ✅ | 上传的文件 |
| `userId` | String | ❌ | 用户标识（`X-User-Id` Header 优先，默认 `debug-user`） |
| `sessionId` | String | ❌ | 绑定会话 ID（可选，沙箱模式首执行时按 session 注入） |

**请求头：**

| Header | 说明 |
|--------|------|
| `X-User-Id` | 网关注入的用户标识，优先于表单参数 `userId` |

**校验规则：**

| 规则 | 默认值 | 说明 |
|------|--------|------|
| 上传开关 | `true` | `file.upload-enabled=false` 时返回 403 |
| 文件大小上限 | 20 MB | `file.upload-max-mb`，超限返回 413 |
| MIME 白名单 | `image/*,text/plain,text/markdown,text/csv,application/pdf,application/vnd.openxmlformats-officedocument.*,application/vnd.ms-*` | `file.upload-allowed-mime`，不匹配返回 415 |
| 扩展名/MIME 交叉校验 | — | 扩展名必须与声明的 MIME 类型一致，不一致返回 400 |
| 待消费文件数上限 | 20 | `file.upload-max-pending`，超限返回 429 |

**危险扩展名黑名单：** `exe`, `bat`, `cmd`, `ps1`, `vbs`, `js`, `wsf`, `msi`, `scr`, `com`, `dll`, `sys`, `reg`, `inf`, `hta`, `cpl`, `msp`, `mst`——即使 MIME 声明为安全类型也会被拒绝。

**成功响应（200）：**

```json
{
  "file_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "file_name": "report.xlsx",
  "mime_type": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  "size": 12345
}
```

**错误响应：**

| 状态码 | error | 说明 |
|--------|-------|------|
| 400 | `no_file_uploaded` | 未提供文件 |
| 400 | `invalid_file_name` | 文件名非法 |
| 400 | `extension_mime_mismatch` | 扩展名与 MIME 不匹配 |
| 403 | `upload_disabled` | 上传功能已关闭 |
| 413 | `file_too_large` | 文件超过大小上限 |
| 415 | `unsupported_file_type` | MIME 不在白名单 |
| 429 | `too_many_pending_files` | 待消费文件数超限 |
| 500 | `storage_write_failed` / `metadata_write_failed` | 存储/元数据写入失败 |

**cURL 示例：**

```bash
curl -X POST "http://localhost:8100/files/upload" \
  -F "file=@report.xlsx" \
  -F "userId=alice" \
  -F "sessionId=acme-test-agent:thread-1"
```

> **文件状态机（沙箱模式）：** 上传文件状态为 `pending`，沙箱容器首次执行时经 files API 注入后置 `injected`；非沙箱模式直接置 `injected` 并写入本地工作区 `uploads/` 目录。

---

### 5.2 GET /files/{fileId}

**文件下载/预览**——流式返回文件字节，支持内联预览。

```
GET /files/{fileId}?inline=1
```

**路径参数：**

| 参数 | 类型 | 说明 |
|------|------|------|
| `fileId` | String | 文件 ID（UUID 格式，防路径遍历） |

**查询参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `inline` | Integer | ❌ | `1`=内联预览（仅 `image/*` 和 `text/*`），默认 `0`=附件下载 |

**响应头：**

| Header | 说明 |
|--------|------|
| `Content-Type` | 文件 MIME 类型 |
| `Content-Length` | 文件字节数 |
| `Content-Disposition` | `attachment` 或 `inline`（含 RFC 5987 编码的文件名） |
| `X-Content-Type-Options` | `nosniff`（防 MIME 嗅探） |

**错误响应：**

| 状态码 | 说明 |
|--------|------|
| 400 | `fileId` 非 UUID 格式 |
| 403 | 下载功能已关闭 |
| 404 | 文件不存在 |
| 502 | 存储后端对象丢失 |

**cURL 示例：**

```bash
# 下载文件
curl -O http://localhost:8100/files/a1b2c3d4-e5f6-7890-abcd-ef1234567890

# 浏览器内联预览图片
curl http://localhost:8100/files/a1b2c3d4-e5f6-7890-abcd-ef1234567890?inline=1
```

---

## 6. Agent 信息接口

### 6.1 GET /

服务信息 + 协议声明。

```json
{
  "agent": "test-agent",
  "slug": "acme-test-agent",
  "version": "1.0.0",
  "description": "A test agent",
  "protocols": {"a2a": "1.0.0", "a2ui": "v0.8", "oaf": "v0.8.0"},
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
    "metadata": "/metadata"
  },
  "engine": "AgentScope Java 2.0"
}
```

### 6.2 GET /health

健康检查。

```json
{
  "status": "healthy",
  "agent": "test-agent",
  "slug": "acme-test-agent",
  "version": "1.0.0",
  "llm_configured": true,
  "engine": "AgentScope Java 2.0",
  "tenant_prefix": "acme-test-agent"
}
```

### 6.3 GET /metadata

Agent 完整元数据。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `includeDetails` | Boolean | ❌ | 是否返回详细信息（tools/subAgents/model/endpoints），默认 `false` |

### 6.4 GET /system-prompt

获取系统提示词。

```json
{
  "system_prompt": "...(完整系统提示词)",
  "base_prompt": "...(OAF 基础提示词)"
}
```

### 6.5 GET /.well-known/agent-card.json

A2A Agent Card 发现端点。

---

## 7. 工具与 MCP 接口

### 7.1 GET /tools

工具列表。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `includeInternal` | Boolean | ❌ | 是否包含内置工具，默认 `false` |

**响应：**

```json
{
  "tools": [
    {
      "name": "write_file",
      "server": "filesystem",
      "category": "mcp",
      "description": "Write content to a file",
      "uiResourceUri": "ui://filesystem/write-file",
      "appOnly": false
    }
  ],
  "totalCount": 5,
  "mcpCount": 5
}
```

| 字段 | 说明 |
|------|------|
| `name` | 工具名 |
| `server` | 来源 MCP 服务器名 |
| `category` | `mcp` / `builtin` |
| `description` | 工具描述 |
| `uiResourceUri` | （可选）MCP Apps UI 资源 URI |
| `appOnly` | （可选）`true` 时仅卡片展示，不在对话中暴露 |

### 7.2 GET /skills

技能列表（只读，动态数据源：frontmatter 声明 ∪ 配置目录实际内容，冲突以目录为准）。

**响应示例：**

```json
[
  {
    "name": "ppt-generation",
    "description": "生成 PPT",
    "version": "1.0.0",
    "source": "local",
    "required": false,
    "dynamic": false,
    "declaredButMissing": false
  },
  {
    "name": "data-analysis",
    "description": "数据分析",
    "version": "",
    "source": "local-dynamic",
    "required": false,
    "dynamic": true,
    "declaredButMissing": false
  }
]
```

| 字段 | 说明 |
|------|------|
| `name` | 技能名 |
| `description` | 描述 |
| `version` | 版本（目录中 SKILL.md frontmatter 的 version） |
| `source` | `local`（frontmatter 声明）/ `local-dynamic`（目录实际内容） |
| `required` | 是否必需（来自 frontmatter 声明） |
| `dynamic` | `true` = 目录中实际存在但 frontmatter 未声明 |
| `declaredButMissing` | `true` = frontmatter 声明但目录中不存在 |

### 7.3 GET /mcp

MCP 服务器列表。

```json
[
  {
    "server": "filesystem",
    "vendor": "block",
    "connection_type": "sse",
    "url": "http://localhost:8811/sse",
    "tool_count": 5,
    "has_ui": true
  }
]
```

### 7.4 GET /mcp/{server}/resources/ui

读取 MCP Apps UI 资源 HTML。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `uri` | String | ✅ | `ui://` 资源 URI |

**响应：**

```json
{
  "html": "<div>...</div>",
  "mimeType": "text/html",
  "csp": "default-src 'self'; script-src 'unsafe-inline'"
}
```

### 7.5 GET /mcp/{server}/resources

列出该 server 全部 `ui://` 资源。

**响应：**

```json
{
  "server": "filesystem",
  "resources": [
    "ui://filesystem/write-file",
    "ui://filesystem/read-file"
  ]
}
```

### 7.6 POST /mcp/{server}/tools/{tool}

UI 卡片代发工具调用。

**请求体：**

```json
{
  "arguments": {"path": "/tmp/test.txt", "content": "hello"},
  "confirmed": false
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `arguments` | Object | ✅ | 工具参数 |
| `confirmed` | Boolean | ❌ | ask 工具是否已确认，默认 `false` |

**成功响应（200）：**

```json
{
  "content": [{"type": "text", "text": "File written successfully"}],
  "isError": false,
  "structuredContent": {}
}
```

**需要确认（403）：**

```json
{
  "needsConfirm": true,
  "toolCalls": [
    {
      "tool_call_id": "uuid-xxx",
      "name": "write_file",
      "input": {"path": "/tmp/test.txt", "content": "hello"}
    }
  ]
}
```

### 7.7 POST /mcp/ui-context

静默更新模型上下文（MCP Apps 4.7）。

**请求体：**

```json
{
  "sessionId": "acme-test-agent:thread-1",
  "content": "当前页面: 订单详情页, 订单号: ORD-001",
  "structuredContent": {"page": "order-detail", "orderId": "ORD-001"}
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sessionId` | String | ✅ | 会话 ID（`tenant:thread` 格式） |
| `content` | String | ❌ | 文本内容（与 structuredContent 至少一个） |
| `structuredContent` | Object | ❌ | 结构化内容 |

**响应：**

```json
{"updated": true, "sessionId": "acme-test-agent:thread-1"}
```

---

## 8. Skill 管理与 @引用接口

### 8.1 @Skill 引用机制

用户可以在消息中使用 `@Skill名称` 来显式引用一个 Skill，系统会在发送给 Agent 之前自动注入该 Skill 的完整内容。

**语法规则：**

- 格式：`@Skill名称`，@ 前不能是单词字符（防止匹配邮箱等）
- Skill 名称支持：中文字符、字母、数字、下划线、连字符、点号
- 同一 Skill 被 @ 多次时只注入一次
- 被禁用的 Skill 的 @ 引用会被保留原样，不注入内容
- 消息中不含 @ 时零开销，直接透传

**注入效果：**

用户发送：
```
帮我用@ppt生成大师 生成一个产品介绍PPT
```

Agent 实际接收到的消息：
```
帮我用[@Skill:ppt生成大师] 生成一个产品介绍PPT

---
## Referenced Skills

### Skill: ppt生成大师
```
（ppt生成大师 SKILL.md 的完整内容）
```

---
```

### 8.2 GET /skills/available

**可用 Skill 摘要列表**——前端输入框中检测到 @ 符号时调用，展示候选 Skill 列表。

```
GET /skills/available
```

**响应示例：**

```json
[
  { "name": "ppt生成大师", "description": "根据主题生成专业PPT演示文稿" },
  { "name": "pdf", "description": "读取、合并、拆分、加密PDF文件" },
  { "name": "xlsx", "description": "处理Excel电子表格文件" }
]
```

> 仅返回已启用（enabled=true）的 Skill，精简 name+description 两字段，降低传输开销。

### 8.3 GET /skills/parse-refs

**解析消息中的 @Skill 引用**——前端在输入过程中可实时调用，预览已匹配的 Skill 列表。

```
GET /skills/parse-refs?message=帮我用@ppt生成大师做PPT
```

**查询参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `message` | String | ✅ | 用户输入的消息片段 |

**响应示例：**

```json
{
  "skills": ["ppt生成大师"],
  "count": 1
}
```

### 8.4 GET /skills/manage

**管理视图**——列出所有 Skill（含被禁用的），每个条目带 `enabled` 字段。供管理页面使用。

```
GET /skills/manage
```

**响应示例：**

```json
[
  {
    "name": "ppt生成大师",
    "description": "根据主题生成专业PPT演示文稿",
    "version": "1.0.0",
    "source": "local-dynamic",
    "required": false,
    "dynamic": true,
    "declaredButMissing": false,
    "enabled": true
  },
  {
    "name": "deprecated-skill",
    "description": "已弃用",
    "version": "0.1.0",
    "source": "local",
    "required": false,
    "dynamic": false,
    "declaredButMissing": false,
    "enabled": false
  }
]
```

### 8.5 Skill 管理操作端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/skills/upload` | POST | 上传 zip 格式的 Skill 包 |
| `/skills/{name}` | DELETE | 删除指定 Skill |
| `/skills/{name}/toggle` | PUT | 切换启停状态 |
| `/skills/{name}/content` | GET | 读取 SKILL.md 内容 |
| `/skills/{name}/content` | PUT | 修改 SKILL.md 内容 |

详细参数与响应格式见 [7.x 工具与 MCP 接口](#7-工具与-mcp-接口) 中的 Skill 相关端点。

---

## 9. SSE 事件词表（完整）

### 9.1 生命周期事件

| type | 触发时机 | 关键字段 |
|------|---------|---------|
| `session_created` | `POST /threads/chat` 未传 sessionId 时自动生成（**仅此端点**） | `session_id` |
| `AGENT_START` | Agent 执行开始 | `replyId`, `sessionId`, `name`, `role` |
| `AGENT_END` | Agent 执行完成（**流关闭信号**） | `replyId` |

**`session_created` 详细结构：**

```json
{
  "type": "session_created",
  "session_id": "auto-generated-uuid"
}
```

> 前端应将此 `session_id` 存入状态，后续调用 `/subscribe`、`/confirm` 等端点需引用此 ID。

### 9.2 模型调用事件

| type | 触发时机 | 关键字段 |
|------|---------|---------|
| `MODEL_CALL_START` | LLM 调用开始 | `replyId` |
| `MODEL_CALL_END` | LLM 调用结束 | `replyId`, `inputTokens`, `outputTokens`, `totalTokens` |

### 9.3 文本流式事件

| type | 触发时机 | 关键字段 |
|------|---------|---------|
| `TEXT_BLOCK_START` | 文本块开始 | `replyId`, `blockId` |
| `TEXT_BLOCK_DELTA` | 文本 token 增量 | `delta`, `replyId`, `blockId` |
| `TEXT_BLOCK_END` | 文本块结束 | `replyId`, `blockId` |

**前端处理：** 按 `blockId` 分组，累加 `delta` 拼接完整文本。

### 9.4 思维链事件

| type | 触发时机 | 关键字段 |
|------|---------|---------|
| `THINKING_BLOCK_START` | 思维块开始 | `replyId`, `blockId` |
| `THINKING_BLOCK_DELTA` | 思维内容增量 | `delta`, `replyId`, `blockId` |
| `THINKING_BLOCK_END` | 思维块结束 | `replyId`, `blockId` |

**前端处理：** 可折叠展示，累加 `delta` 拼接完整思维内容。

### 9.5 多模态数据事件

| type | 触发时机 | 关键字段 |
|------|---------|---------|
| `DATA_BLOCK_START` | 数据块开始 | `replyId`, `blockId` |
| `DATA_BLOCK_DELTA` | 数据增量 | `delta`, `replyId`, `blockId` |
| `DATA_BLOCK_END` | 数据块结束 | `replyId`, `blockId` |

### 9.6 工具调用事件

| type | 触发时机 | 关键字段 |
|------|---------|---------|
| `TOOL_CALL_START` | 工具调用开始 | `toolName`, `toolCallId`, `replyId`, `ui`（可选） |
| `TOOL_CALL_DELTA` | 工具参数增量 | `delta`, `toolCallId`, `toolCallName` |
| `TOOL_CALL_END` | 工具调用参数结束 | `toolCallId`, `toolCallName` |
| `TOOL_RESULT_START` | 工具结果开始 | `toolCallId`, `toolCallName` |
| `TOOL_RESULT_TEXT_DELTA` | 工具结果文本增量 | `delta`, `toolCallId`, `toolCallName` |
| `TOOL_RESULT_END` | 工具结果结束 | `state`, `toolCallId`, `toolCallName` |

**`TOOL_CALL_START` 的 `ui` 字段（MCP Apps 扩展）：**

```json
{
  "type": "TOOL_CALL_START",
  "toolName": "write_file",
  "toolCallId": "call-abc",
  "replyId": "reply-001",
  "ui": {
    "resourceUri": "ui://filesystem/write-file",
    "server": "filesystem"
  }
}
```

前端收到 `ui` 字段后，可通过 `GET /mcp/{server}/resources/ui?uri=ui://...` 拉取卡片 HTML 渲染。

**`TOOL_RESULT_END.state` 枚举值：** `COMPLETE` / `ERROR`

### 9.7 文件产出事件

| type | 触发时机 | 关键字段 |
|------|---------|---------|
| `file_ready` | Agent 产出文件（`present_file` / `create_oaf_zip` 工具完成） | `file_id`, `file_name`, `mime_type`, `size`, `download_url` |

**`file_ready` 详细结构：**

```json
{
  "type": "file_ready",
  "file_id": "f1e2d3c4-b5a6-7890-abcd-ef1234567890",
  "file_name": "report.xlsx",
  "mime_type": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  "size": 12345,
  "download_url": "/files/f1e2d3c4-b5a6-7890-abcd-ef1234567890"
}
```

**前端处理：** 收到 `file_ready` 后渲染文件下载卡片，`download_url` 可直接拼接 Base URL 用于下载或预览。

### 9.8 HITL 确认事件

| type | 触发时机 | 关键字段 |
|------|---------|---------|
| `permission_ask` | 工具调用被 ASK 拦截，需人工确认 | `tool_calls[]`, `reply_id` |

**`permission_ask` 详细结构：**

```json
{
  "type": "permission_ask",
  "id": "evt-003",
  "tool_calls": [
    {
      "tool_call_id": "call-abc",
      "name": "write_file",
      "input": {"path": "/tmp/test.txt", "content": "hello"}
    },
    {
      "tool_call_id": "call-def",
      "name": "delete_file",
      "input": {"path": "/tmp/old.txt"}
    }
  ],
  "reply_id": "reply-001"
}
```

> **收到此事件后 SSE 流关闭**，前端渲染确认卡片，用户决策后走 `confirm` 或 `confirm-stream`。

### 9.9 控制帧

| type | 触发时机 | 关键字段 |
|------|---------|---------|
| `waiting` | 排队等待（每 15s 一帧） | 无 |
| `done` | 历史回放结束（仅 `/subscribe` 已完成 turn 时出现） | 无 |
| `error` | 执行错误 | `error`（错误信息字符串） |

---

## 10. 错误处理

流式场景下错误以 SSE error 帧返回：

```
data: {"type":"error","error":"turn_in_progress: session 'xxx' has an active turn and queue timeout reached"}
```

| error 值 | 说明 |
|----------|------|
| `turn_in_progress: session '...' has an active turn...` | 排队超时（120s），同 session 有活跃执行 |
| `confirm_context_not_found: ...` | 确认上下文不存在或已过期 |
| `confirm_already_consumed` | 重复确认 |
| 其他 | 运行时异常信息 |

### 10.2 HTTP 错误码

| 状态码 | 场景 |
|--------|------|
| 400 | 请求参数非法 |
| 403 | MCP 工具需要确认（`needsConfirm: true`） |
| 404 | 资源不存在 / confirm_context_not_found |
| 409 | 冲突 / confirm_already_consumed |
| 500 | 服务端内部错误 |

---

## 11. 前端对接指南

```javascript
// 推荐使用 fetch + ReadableStream 读取 SSE
async function sendChat(sessionId, message, userId, fileIds) {
  const body = { message };
  if (userId) body.userId = userId;
  if (fileIds && fileIds.length > 0) body.fileIds = fileIds;

  const response = await fetch(`/threads/${sessionId}/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let lastSeq = 0;

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    const text = decoder.decode(value);
    const lines = text.split('\n').filter(l => l.startsWith('data: '));
    for (const line of lines) {
      const event = JSON.parse(line.slice(6));
      if (event.seq) lastSeq = event.seq;
      handleEvent(event);
    }
  }
  return lastSeq; // 用于断连续传
}

// SSE 断连续传
async function resumeStream(sessionId, lastSeq) {
  const response = await fetch(
    `/threads/${sessionId}/subscribe?afterSeq=${lastSeq}`,
    { headers: { 'Accept': 'text/event-stream' } }
  );
  const reader = response.body.getReader();
  const decoder = new TextDecoder();

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    const text = decoder.decode(value);
    const lines = text.split('\n').filter(l => l.startsWith('data: '));
    for (const line of lines) {
      const event = JSON.parse(line.slice(6));
      handleEvent(event);
    }
  }
}

// 文件上传
async function uploadFile(file, userId, sessionId) {
  const form = new FormData();
  form.append('file', file);
  if (userId) form.append('userId', userId);
  if (sessionId) form.append('sessionId', sessionId);

  const resp = await fetch('/files/upload', { method: 'POST', body: form });
  return resp.json(); // { file_id, file_name, mime_type, size }
}
```

### 11.2 事件渲染逻辑

```javascript
function handleEvent(event) {
  switch (event.type) {
    // === 生命周期 ===
    case 'session_created':
      onSessionCreated(event.session_id);
      break;
    case 'AGENT_START':
      onStreamStart(event);
      break;
    case 'AGENT_END':
      onStreamEnd(event);
      break;

    // === 文本流式（累加 delta） ===
    case 'TEXT_BLOCK_DELTA':
      appendText(event.blockId, event.delta);
      break;

    // === 思维链（可折叠展示） ===
    case 'THINKING_BLOCK_DELTA':
      appendThinking(event.blockId, event.delta);
      break;

    // === 工具调用 ===
    case 'TOOL_CALL_START':
      showToolCall(event.toolCallId, event.toolName, event.ui);
      break;
    case 'TOOL_RESULT_END':
      updateToolResult(event.toolCallId, event.state);
      break;

    // === 文件产出 ===
    case 'file_ready':
      showFileCard(event.file_id, event.file_name, event.download_url, event.mime_type, event.size);
      break;

    // === HITL 确认 ===
    case 'permission_ask':
      showConfirmCard(event.tool_calls, event.reply_id);
      break;

    // === 控制帧 ===
    case 'waiting':
      showWaitingStatus();
      break;
    case 'done':
      onReplayComplete();
      break;
    case 'error':
      showError(event.error);
      break;
  }
}
```

### 11.3 HITL 确认流程

```
1. 收到 permission_ask → 渲染确认卡片（工具名 + 参数 JSON）
2. 用户点击「批准」/「拒绝」
3. 调用确认接口：

   // 方式一：流式确认（推荐，实时展示恢复过程）
   POST /threads/{sessionId}/confirm-stream
   → 复用同一 handleEvent 渲染恢复事件

   // 方式二：同步确认（简单，只拿最终结果）
   POST /threads/{sessionId}/confirm
   → 直接拿到最终回复文本
```

### 11.4 页面刷新恢复

```
1. 从 localStorage 恢复当前 sessionId 和 lastSeq
2. GET /threads/{sessionId}/status → 判断 state
   - working / waiting_confirm → GET /subscribe?afterSeq=lastSeq 续传
   - completed → GET /subscribe?afterSeq=lastSeq 回放（收到 done 帧后关闭）
   - idle → 正常显示空白输入
3. 并行 GET /threads/{sessionId}/history → 回显消息 + pendingConfirm + files
   - pendingConfirm 存在 → 弹确认卡片 → 走确认流程
   - files 非空 → 渲染产出文件卡片
```

### 11.5 并发请求处理

- 同 session 并发请求自动排队，收到 `waiting` 帧时展示"排队等待中"
- 可提供"停止"按钮 abort 连接（无服务端副作用）
- 排队超时 120s 后返回 error 帧

---

## 12. 数据表参考

### confirm_context

HITL 确认上下文，跨副本持久化。

| 列 | 类型 | 说明 |
|----|------|------|
| `session_id` | VARCHAR(255) PK | 会话 key |
| `tool_calls_json` | MEDIUMTEXT | 待确认工具调用列表（JSON 字符串） |
| `reply_id` | VARCHAR(64) | 触发确认的 reply 标识 |
| `created_at` | DATETIME(3) | 创建时间 |
| `consumed` | TINYINT(1) | 0=待确认，1=已消费 |

**TTL:** 30 分钟

### turn_lease

Turn 租约，同一 session 执行段串行化。

| 列 | 类型 | 说明 |
|----|------|------|
| `session_id` | VARCHAR(255) PK | 会话 key |
| `token` | CHAR(36) | 租约 token（UUID） |
| `expires_at` | DATETIME(3) | 过期时间 |

**TTL:** 60 秒，续租间隔 20 秒

### session_event

SSE 事件持久化，支持断连续传回放。

| 列 | 类型 | 说明 |
|----|------|------|
| `id` | BIGINT PK AUTO_INCREMENT | 自增 ID |
| `session_id` | VARCHAR(255) | 会话 key |
| `seq` | INT | 事件序号（同一 session 单调递增） |
| `reply_id` | VARCHAR(64) | 回复 ID |
| `type` | VARCHAR(64) | 事件类型 |
| `payload` | MEDIUMTEXT | 完整事件 JSON |
| `created_at` | DATETIME(3) | 创建时间 |

**索引:** `idx_session_seq (session_id, seq)`，**保留期:** 与 agent_state 对齐（默认 7 天）

### tool_audit_log

工具调用审计日志。

| 列 | 类型 | 说明 |
|----|------|------|
| `id` | BIGINT PK | 自增 ID |
| `session_id` | VARCHAR(255) | 会话 key |
| `tool_name` | VARCHAR(255) | 工具名 |
| `tool_call_id` | VARCHAR(64) | 调用 ID |
| `state` | VARCHAR(32) | 事件类型 |
| `payload_json` | MEDIUMTEXT | 完整事件 JSON |
| `created_at` | DATETIME(3) | 创建时间 |

**保留期:** 30 天

### file_asset

文件资产元数据（字节存储在 FileStorage 后端）。

| 列 | 类型 | 说明 |
|----|------|------|
| `id` | VARCHAR(36) PK | 文件 ID（UUID） |
| `user_key` | VARCHAR(255) | 用户标识（userId） |
| `session_id` | VARCHAR(255) | 绑定会话 ID（可选） |
| `file_name` | VARCHAR(255) | 文件名 |
| `workspace_path` | VARCHAR(512) | 工作区相对路径（注入后回写） |
| `mime_type` | VARCHAR(128) | MIME 类型 |
| `size` | BIGINT | 文件字节数 |
| `storage_type` | VARCHAR(16) | 存储后端类型（local / s3） |
| `storage_key` | VARCHAR(512) | 存储后端对象 key |
| `origin` | VARCHAR(16) | 来源（upload / generated） |
| `status` | VARCHAR(16) | 状态（pending / injected） |
| `created_at` | DATETIME(3) | 创建时间 |

**索引:** `idx_user_status (user_key, status)`、`idx_session (session_id, created_at)`、`uk_storage (storage_type, storage_key)`

**保留期:** upload 文件 7 天（P2 定时清理）

---

## 附录 A：完整 SSE 事件时序示例

### A.1 普通对话（无工具调用）

```
→ POST /threads/acme-test-agent:t1/chat  {"message":"你好","userId":"alice"}

data: {"type":"AGENT_START","replyId":"r1","sessionId":"acme-test-agent:t1","id":"e1"}
data: {"type":"MODEL_CALL_START","replyId":"r1","id":"e2"}
data: {"type":"TEXT_BLOCK_START","replyId":"r1","blockId":"b1","id":"e3"}
data: {"type":"TEXT_BLOCK_DELTA","delta":"你","replyId":"r1","blockId":"b1","id":"e4"}
data: {"type":"TEXT_BLOCK_DELTA","delta":"好","replyId":"r1","blockId":"b1","id":"e5"}
data: {"type":"TEXT_BLOCK_DELTA","delta":"！","replyId":"r1","blockId":"b1","id":"e6"}
data: {"type":"TEXT_BLOCK_END","replyId":"r1","blockId":"b1","id":"e7"}
data: {"type":"MODEL_CALL_END","replyId":"r1","inputTokens":10,"outputTokens":8,"totalTokens":18,"id":"e8"}
data: {"type":"AGENT_END","replyId":"r1","id":"e9"}
```

### A.2 工具调用 + HITL 确认 + 恢复

```
→ POST /threads/acme-test-agent:t1/chat  {"message":"写文件","userId":"alice"}

data: {"type":"AGENT_START","replyId":"r1",...,"id":"e1"}
data: {"type":"MODEL_CALL_START","replyId":"r1","id":"e2"}
data: {"type":"THINKING_BLOCK_START","replyId":"r1","blockId":"b1","id":"e3"}
data: {"type":"THINKING_BLOCK_DELTA","delta":"用户想写文件","replyId":"r1","blockId":"b1","id":"e4"}
data: {"type":"THINKING_BLOCK_END","replyId":"r1","blockId":"b1","id":"e5"}
data: {"type":"TOOL_CALL_START","toolName":"write_file","toolCallId":"call-abc","replyId":"r1","ui":{"resourceUri":"ui://filesystem/write-file","server":"filesystem"},"id":"e6"}
data: {"type":"TOOL_CALL_END","toolCallId":"call-abc","toolCallName":"write_file","id":"e7"}
data: {"type":"MODEL_CALL_END","replyId":"r1","inputTokens":50,"outputTokens":30,"totalTokens":80,"id":"e8"}
← （Agent 暂停，确认上下文落库）
data: {"type":"permission_ask","tool_calls":[{"tool_call_id":"call-abc","name":"write_file","input":{"path":"/tmp/test.txt","content":"hello"}}],"reply_id":"r1","id":"e9"}
（流关闭）

→ 用户点击「批准」

→ POST /threads/acme-test-agent:t1/confirm-stream
   {"results":[{"tool_call_id":"call-abc","confirmed":true,"accept_rule":false}]}

data: {"type":"AGENT_START","replyId":"r1",...,"id":"e10"}
data: {"type":"TOOL_RESULT_START","toolCallId":"call-abc","toolCallName":"write_file",...,"id":"e11"}
data: {"type":"TOOL_RESULT_TEXT_DELTA","delta":"File written successfully","toolCallId":"call-abc",...,"id":"e12"}
data: {"type":"TOOL_RESULT_END","state":"COMPLETE","toolCallId":"call-abc",...,"id":"e13"}
data: {"type":"TEXT_BLOCK_START","replyId":"r1","blockId":"b10",...,"id":"e14"}
data: {"type":"TEXT_BLOCK_DELTA","delta":"文件已写入","replyId":"r1","blockId":"b10",...,"id":"e15"}
data: {"type":"AGENT_END","replyId":"r1","id":"e16"}
```

### A.3 文件上传 + 对话 + 产出文件

```
→ 1. 上传文件
POST /files/upload  (multipart/form-data: file=report.xlsx, userId=alice)
← {"file_id":"f1e2d3c4-...","file_name":"report.xlsx","mime_type":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","size":12345}

→ 2. 携带 fileIds 发起对话
POST /threads/acme-test-agent:t1/chat
{"message":"分析这份报表并生成汇总","userId":"alice","fileIds":["f1e2d3c4-..."]}

data: {"type":"AGENT_START","replyId":"r1",...,"id":"e1"}
data: {"type":"MODEL_CALL_START","replyId":"r1","id":"e2"}
data: {"type":"TOOL_CALL_START","toolName":"read_file","toolCallId":"call-001","replyId":"r1","id":"e3"}
data: {"type":"TOOL_CALL_END","toolCallId":"call-001","toolCallName":"read_file","id":"e4"}
data: {"type":"MODEL_CALL_END","replyId":"r1",...,"id":"e5"}
（Agent 读取上传文件，多轮工具调用...）
data: {"type":"TOOL_CALL_START","toolName":"present_file","toolCallId":"call-010","replyId":"r1","id":"e20"}
data: {"type":"TOOL_CALL_END","toolCallId":"call-010","toolCallName":"present_file","id":"e21"}
data: {"type":"TOOL_RESULT_START","toolCallId":"call-010","toolCallName":"present_file","id":"e22"}
data: {"type":"TOOL_RESULT_TEXT_DELTA","delta":"{\"file_id\":\"g7h8i9j0-...\",\"file_name\":\"summary.xlsx\",...}","toolCallId":"call-010","id":"e23"}
data: {"type":"TOOL_RESULT_END","state":"COMPLETE","toolCallId":"call-010","id":"e24"}
（present_file 工具完成 → 合成 file_ready 帧）
data: {"type":"file_ready","file_id":"g7h8i9j0-...","file_name":"summary.xlsx","mime_type":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","size":6789,"download_url":"/files/g7h8i9j0-...","id":"e25"}
data: {"type":"TEXT_BLOCK_START","replyId":"r1","blockId":"b30","id":"e26"}
data: {"type":"TEXT_BLOCK_DELTA","delta":"汇总报表已生成","replyId":"r1","blockId":"b30","id":"e27"}
data: {"type":"AGENT_END","replyId":"r1","id":"e28"}
```

### A.4 SSE 断连续传

```
（SSE 在 seq=42 时断连，前端记录 lastSeq=42）

→ GET /threads/acme-test-agent:t1/subscribe?afterSeq=42

（先回放 seq 43~50 的历史事件，再订阅实时流）
data: {"type":"TEXT_BLOCK_DELTA","delta":"继续","replyId":"r1","blockId":"b1","seq":43,"id":"e43"}
...
data: {"type":"AGENT_END","replyId":"r1","seq":50,"id":"e50"}
（Turn 已完成 → 追加 done 帧）
data: {"type":"done"}
（流关闭）
```
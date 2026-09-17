# 会话管理 API 对接规范

> 版本：v1.0 | 更新：2026-09-10

## 一、接口总览

| 方法 | 路径 | 说明 | Content-Type |
|------|------|------|-------------|
| POST | `/threads/chat` | 发送对话消息（SSE 流式响应，sessionId 在请求体） | `text/event-stream` |
| POST | `/threads/{sessionId}/confirm-stream` | HITL 确认恢复（SSE 流式响应） | `text/event-stream` |
| POST | `/threads/{sessionId}/confirm` | HITL 确认恢复（同步 JSON 响应） | `application/json` |
| GET | `/threads/{sessionId}/subscribe` | SSE 断连续传 | `text/event-stream` |
| GET | `/threads/{sessionId}/status` | 查询 turn 状态 | `application/json` |
| GET | `/threads/{sessionId}` | 会话详情（元信息+消息历史） | `application/json` |
| GET | `/threads/{sessionId}/history` | 历史消息 | `application/json` |
| GET | `/threads/{sessionId}/llm-calls` | LLM 调用记录 | `application/json` |
| GET | `/threads` | 会话列表 | `application/json` |
| DELETE | `/threads/{sessionId}` | 删除会话（级联清理所有关联数据） | `application/json` |
| PATCH | `/threads/{sessionId}` | 更新会话（目前支持重命名） | `application/json` |

---

## 二、公共约定

### 2.1 用户身份

优先级：`X-User-Id` 请求头 > 请求体 `userId` 字段 > 默认值 `debug-user`

```
X-User-Id: user-123
```

### 2.2 sessionId 规则

- 由前端生成（建议 `crypto.randomUUID()`）
- 同一 `sessionId` 反复调 `/chat` = 继续同一会话（自动带记忆）
- 换 `sessionId` = 新会话（无历史）
- 路径中不允许包含 `/`、`\`、`:`（后端自动 sanitize）

### 2.3 Turn 串行保证

同一 `sessionId` 同时只能有一个 turn 在执行。如果上一次还没完成就发新消息，SSE 会返回：

```json
{"type":"error","error":"turn_in_progress: session 'xxx' has an active turn and queue timeout reached"}
```

**前端必须等收到 `{"type":"done"}` 后再发下一条消息。**

---

## 三、接口详情

### 3.1 POST /threads/chat — 发送消息

**请求体：**

```json
{
  "message": "帮我分析一下销售数据",     // 必填（或 fileIds 至少一项）
  "userId": "user-123",                 // 可选，X-User-Id 优先
  "sessionId": "my-session-1",          // 可选，省略则自动生成 UUID 并首发 session_created
  "fileIds": ["file-abc", "file-def"]   // 可选，上传文件 ID
}
```

**响应：** SSE 事件流（`text/event-stream`）

```
id: 1
data: {"type":"AGENT_START","id":"evt-1","replyId":"rid-xxx"}

id: 2
data: {"type":"MODEL_CALL_START","id":"evt-2","replyId":"rid-xxx"}

id: 3
data: {"type":"TEXT_BLOCK_DELTA","id":"evt-3","delta":"你","replyId":"rid-xxx","blockId":"blk-1"}

id: 4
data: {"type":"TEXT_BLOCK_DELTA","id":"evt-4","delta":"好","replyId":"rid-xxx","blockId":"blk-1"}

id: 5
data: {"type":"TOOL_CALL_START","id":"evt-5","toolName":"read_file","toolCallId":"tc-1","replyId":"rid-xxx"}

id: 6
data: {"type":"TOOL_CALL_DELTA","id":"evt-6","delta":"{\"path\":\"data.csv\"}","toolCallId":"tc-1","toolCallName":"read_file"}

id: 7
data: {"type":"TOOL_CALL_END","id":"evt-7","toolCallId":"tc-1","toolCallName":"read_file"}

id: 8
data: {"type":"TOOL_RESULT_START","id":"evt-8","toolCallId":"tc-1","toolCallName":"read_file"}

id: 9
data: {"type":"TOOL_RESULT_TEXT_DELTA","id":"evt-9","delta":"文件内容...","toolCallId":"tc-1","toolCallName":"read_file"}

id: 10
data: {"type":"TOOL_RESULT_END","id":"evt-10","state":"SUCCESS","toolCallId":"tc-1","toolCallName":"read_file"}

id: 11
data: {"type":"MODEL_CALL_END","id":"evt-11","inputTokens":500,"outputTokens":200,"totalTokens":700}

id: 12
data: {"type":"AGENT_END","id":"evt-12","replyId":"rid-xxx"}

id: 13
data: {"type":"done"}
```

#### HITL 场景（工具需人工确认）

当 Agent 请求确认时，SSE 会发 `permission_ask` 事件代替 `done`：

```
id: 8
data: {"type":"permission_ask","id":"evt-8","reply_id":"rid-xxx","tool_calls":[{"tool_call_id":"tc-2","name":"execute","input":{"command":"rm -rf /tmp/test"}}]}
```

此时前端弹出确认卡片，用户点击批准/拒绝后调用 `confirm-stream`。

#### 排队等待

如果当前有其他 turn 在执行，SSE 会持续发 waiting 帧保持连接：

```
data: {"type":"waiting"}
```

### 3.2 POST /threads/{sessionId}/confirm-stream — 确认恢复

**请求体：**

```json
{
  "results": [
    {
      "tool_call_id": "tc-2",
      "confirmed": true,
      "accept_rule": false
    }
  ]
}
```

**响应：** 与 `/chat` 相同格式的 SSE 流。

### 3.3 GET /threads/{sessionId}/subscribe — 断连续传

页面刷新后，根据 `Last-Event-ID` 续传：

```
GET /threads/my-session-001/subscribe?afterSeq=13
```

- `afterSeq`：上次收到的最后一个事件 ID，从该位置之后回放
- `replyId`（可选）：仅订阅指定 turn 的事件

如果 turn 已完成，回放历史后追加 `done` 帧并关闭。

### 3.4 GET /threads/{sessionId}/status — 查询状态

**响应：**

```json
{
  "session_id": "my-session-001",
  "state": "working",
  "latest_event_seq": 42,
  "reply_id": "rid-xxx",
  "pending_confirm": ""
}
```

| state | 含义 |
|-------|------|
| `working` | Agent 正在执行 |
| `completed` | Turn 已完成 |
| `waiting_confirm` | 等待人工确认（`pending_confirm` 非空） |
| `idle` | 空闲，等待用户输入 |

### 3.5 GET /threads/{sessionId} — 会话详情

**响应：**

```json
{
  "session_id": "my-session-001",
  "user_id": "user-123",
  "updated_at": "2026-09-10T10:30:00",
  "title": "销售数据分析",
  "pendingConfirm": null,
  "files": [
    {"file_id": "f-1", "file_name": "report.xlsx", "mime_type": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "size": 12345}
  ],
  "messages": [
    {"role": "user", "content": "帮我分析一下销售数据"},
    {"role": "assistant", "content": "好的，让我读取数据文件..."}
  ]
}
```

### 3.6 GET /threads — 会话列表

```
GET /threads?userId=user-123
```

**响应：**

```json
[
  {
    "session_id": "my-session-001",
    "thread_id": "001",
    "user_id": "user-123",
    "title": "销售数据分析",
    "updated_at": "2026-09-10T10:30:00"
  },
  {
    "session_id": "my-session-002",
    "thread_id": "002",
    "user_id": "user-123",
    "title": "",
    "updated_at": "2026-09-09T15:00:00"
  }
]
```

### 3.7 DELETE /threads/{sessionId} — 删除会话

级联清理所有关联数据（agent_state、agent_fs、session_user、confirm_context、turn_lease、file_asset；session_event 已迁 Redis，对 `sess:{sid}:events` / `sess:{sid}:replies` 两把 key 做一次 `DEL`，删得的 key 数计入 rows_affected）。

**响应：**

```json
{
  "session_id": "my-session-001",
  "deleted": true,
  "rows_affected": 15
}
```

### 3.8 PATCH /threads/{sessionId} — 更新会话

**请求体：**

```json
{
  "title": "Q3 销售分析"
}
```

**响应：**

```json
{
  "session_id": "my-session-001",
  "title": "Q3 销售分析"
}
```

> `title` 存储在 `session_user.remark` 字段（自动 DDL 添加）

---

## 四、SSE 事件词表

### 4.1 完整事件类型

| type | 方向 | 关键字段 | 说明 |
|------|------|---------|------|
| `AGENT_START` | S→C | `replyId` | Turn 开始 |
| `AGENT_END` | S→C | `replyId` | Turn 结束 |
| `MODEL_CALL_START` | S→C | `replyId` | LLM 调用开始 |
| `MODEL_CALL_END` | S→C | `inputTokens`, `outputTokens`, `totalTokens` | LLM 调用结束 |
| `TEXT_BLOCK_START` | S→C | `replyId`, `blockId` | 文本块开始 |
| `TEXT_BLOCK_DELTA` | S→C | `delta`, `replyId`, `blockId` | 文本增量（逐 token） |
| `TEXT_BLOCK_END` | S→C | `replyId`, `blockId` | 文本块结束 |
| `THINKING_BLOCK_START` | S→C | `replyId`, `blockId` | 思维链开始 |
| `THINKING_BLOCK_DELTA` | S→C | `delta`, `replyId`, `blockId` | 思维链增量 |
| `THINKING_BLOCK_END` | S→C | `replyId`, `blockId` | 思维链结束 |
| `DATA_BLOCK_START` | S→C | `replyId`, `blockId` | 多模态数据块开始 |
| `DATA_BLOCK_DELTA` | S→C | `delta`, `replyId`, `blockId` | 多模态数据增量 |
| `DATA_BLOCK_END` | S→C | `replyId`, `blockId` | 多模态数据块结束 |
| `TOOL_CALL_START` | S→C | `toolName`, `toolCallId`, `replyId` | 工具调用开始 |
| `TOOL_CALL_DELTA` | S→C | `delta`, `toolCallId`, `toolCallName` | 工具输入参数增量 |
| `TOOL_CALL_END` | S→C | `toolCallId`, `toolCallName` | 工具调用结束 |
| `TOOL_RESULT_START` | S→C | `toolCallId`, `toolCallName` | 工具结果开始 |
| `TOOL_RESULT_TEXT_DELTA` | S→C | `delta`, `toolCallId`, `toolCallName` | 工具结果文本增量 |
| `TOOL_RESULT_DATA_DELTA` | S→C | `tool_call_id`, `tool_call_name`, `media_type`, `data`/`url` | 工具结果二进制增量 |
| `TOOL_RESULT_END` | S→C | `state`, `toolCallId`, `toolCallName` | 工具结果结束 |
| `permission_ask` | S→C | `reply_id`, `tool_calls[]` | HITL 请求确认 |
| `file_ready` | S→C | `file_id`, `file_name`, `mime_type`, `size`, `download_url` | 产出文件就绪 |
| `waiting` | S→C | — | 排队等待租约 |
| `error` | S→C | `error` | 错误 |
| `done` | S→C | — | Turn 完成 |

### 4.2 心跳

无业务事件时，服务端每 20s 发送 SSE comment 帧：

```
: hb
```

前端忽略即可，作用是防止 Nginx/CDN 的 60s 读超时断连。

---

## 五、前端状态机

```
                    ┌──────────────┐
                    │    IDLE      │ ← 初始/收到 done
                    └──────┬───────┘
                           │ 用户发送消息
                           ▼
                    ┌──────────────┐
               ┌──▶│   WORKING    │◀──┐
               │   └──────┬───────┘   │
               │          │           │
               │   ┌──────▼───────┐   │
               │   │WAITING_CONFIRM│   │ 用户 confirm
               │   └──────┬───────┘   │
               │          │           │
               │   用户 confirm       │
               │          │           │
               │          └───────────┘
               │
          error/abort
               │
               ▼
         回到 IDLE
```

---

## 六、刷新恢复流程

```
页面加载
  │
  ├── 1. 从 localStorage 取 sessionId
  │
  ├── 2. GET /threads/{sessionId}/status
  │     │
  │     ├── state=idle/completed
  │     │     → GET /threads/{sessionId} 还原消息，等待用户输入
  │     │
  │     ├── state=working
  │     │     → GET /threads/{sessionId} 还原消息
  │     │     → GET /threads/{sessionId}/subscribe?afterSeq=N 续传 SSE
  │     │
  │     └── state=waiting_confirm
  │           → GET /threads/{sessionId} 还原消息 + pendingConfirm
  │           → 弹出确认卡片
  │
  └── 3. 无 sessionId → 生成新的，展示空会话
```

---

## 七、错误码

| HTTP | SSE error | 说明 |
|------|-----------|------|
| 400 | `message or fileIds is required` | 请求体缺少 message 和 fileIds |
| 404 | `confirm_context_not_found` | 确认上下文不存在或已过期 |
| 409 | `confirm_already_consumed` | 确认已被处理 |
| 409 | `turn_in_progress` | 会话正在执行中，等 done 后重试 |
| 500 | `error` | 服务端内部错误 |

---

## 八、注意事项

1. **不能用 EventSource** — `/chat` 是 POST 请求，`EventSource` 只支持 GET。用 `fetch` + `ReadableStream` 消费。

2. **必须等 done** — 连续发消息必须等 `{"type":"done"}` 后再发，否则会收到 `turn_in_progress` 错误。

3. **sessionId 持久化** — 存 `localStorage`，刷新后能恢复。换 sessionId = 新会话。

4. **上下文压缩** — 超过 30 条消息自动压缩，保留语义但不保留原文。重要信息建议用户主动保存。

5. **会话保留期** — `agent_state` 默认 7 天，过期后由每日定时清理；`session_event`（Redis）留存 = 每次**写入续期**的 7 天 TTL 与单 session 25 万条上限取小，即「最后一次写入后 7 天」，不参与每日清理。`session_user` 映射同步清理。

6. **文件上传** — 先调 `POST /files/upload` 获取 `fileId`，再在 `/chat` 的 `fileIds` 中引用。

7. **CORS** — 如前后端分离部署，需配置 CORS 允许 `text/event-stream` 响应头。

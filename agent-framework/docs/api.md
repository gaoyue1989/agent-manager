# Agent Framework — API 文档

**版本:** v2.1.0 (Java) — 无状态单次流架构
**复核日期:** 2026-10-08（分支 docs/code-review-2026-10-01）— 增量重核：Flyway V9（#87 PER_PEER 会话重键）、
llm-calls 记录键 gw-hash 形态订正
（上一轮 2026-10-01 @ `4a4dea8`：数据表迁 Flyway（V1–V8）、模型采样参数、confirm_context 多行化、
`/a2a/jobs`、ASKING 入口预检、远程转发事件标注；
2026-09-26 @ `a263b92` 已按源码重核端点清单、`/tools` 契约、数据表与事件类型）

> **现状核对**：本篇是 REST 契约权威，与 [api-frontend-sse.md](api-frontend-sse.md)（前端视角）、
> [api-thread-spec.md](api-thread-spec.md)（会话协议契约，E2E 断言权威）并行。
> 面向使用者的上手流程见 [agent-creation-guide.md](agent-creation-guide.md)。

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
        "oaf": "v0.8.0",
        "agent_protocol": {"enabled": false, "streaming": false, "hitl": false, "task_store": ""}
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
        "metadata": "/metadata"
    },
    "engine": "AgentScope Java 2.0"
}
```

> `protocols.agent_protocol` 为远程子 agent（Agent Protocol）能力开关，与 `/.well-known/agent-card.json` 同源（`InfoController.agentProtocolStatus`）；`GET /metadata` 另返回 `agent_protocol` 与 `a2a_job`（A2A 幂等 Job 状态）两个顶层字段。

---

### GET /metadata

完整 Agent 元数据（skills 返回对象数组而非数量；`?includeDetails=true` 追加 tools/subAgents/model/endpoints）。

```bash
curl http://localhost:8100/metadata
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

技能列表。返回「OAF frontmatter 声明 ∪ `/config/skills` 目录事实」的合并视图。

```bash
curl http://localhost:8100/skills
```

**响应：** `[{name, description, version, source, required, dynamic, declaredButMissing}]`

> `dynamic: true` 表示该技能由 `/config/skills` 目录发现（每轮重扫，**新增后下一轮对话即生效**）；
> `declaredButMissing: true` 表示在 AGENTS.md 里声明了但目录缺失（仅告警不阻断启动）。

---

## 技能管理 API

`/config/skills` 目录是**只读**的 OAF 包内容，因此管理面走 `agent_fs`（可写区），分两档：
包内基线（L2，动态加载）+ 用户个人技能（L4，按 userId 隔离）。设计见
[oaf-skills-dynamic-loading-plan.md](oaf-skills-dynamic-loading-plan.md)。

### 包内技能管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/skills/manage` | 全部技能（含禁用项，多一个 `enabled` 字段） |
| GET | `/skills/available` | `@` 补全候选。按 `X-User-Id`（或 `?userId=`）合并该用户的 L4 技能 |
| GET | `/skills/parse-refs?message=` | 预览消息中的 `@Skill` 解析结果：`{skills:[...], count}` |
| POST | `/skills/upload` | multipart `file`（.zip，≤20MB），返回 `{name, message, skill?}` |
| DELETE | `/skills/{name}` | 删除（校验无 `..` / `/` / `\` 开头 `.`） |
| PUT | `/skills/{name}/toggle` | 启停切换：`{name, enabled, message}` |
| GET | `/skills/{name}/content` | 读 `SKILL.md`：`{name, content}` |
| PUT | `/skills/{name}/content` | 写 `SKILL.md`，body `{content}`（≤100KB） |

> `GET /skills/users/{userId}` 与 `GET /skills/{name}/content` 在 `userId == "content"` 时同时匹配，
> 由更具体的 `/skills/{name}/content` 命中（Spring 路由优先级）。

### 用户个人技能（L4）

命名空间 `agents/{agent}/users/{uid}/skills`。生效范围分档：非沙箱档下一轮会话生效；
沙箱档由「会话开始物化 L4」投影进容器，在该用户下一个 turn 生效。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/skills/users` | 有个人技能的用户索引：`{count, users:[...], truncated?}` |
| GET | `/skills/users/{userId}` | 该用户的 L4 技能：`{userId, skills, tombstones}` |
| GET | `/skills/users/{userId}/{name}` | 读单个个人技能。query `file` 默认 `SKILL.md`（只读） |
| GET | `/skills/users/{userId}/{name}/download` | **下载技能整目录为 zip**（`{name}.zip`；有 L4 覆盖导 L4，否则回落包内基线；两侧都无 → 404） |
| PUT | `/skills/users/{userId}/{name}` | 写个人技能，body `{content}` |
| POST | `/skills/users/{userId}/upload` | **zip 上传个人技能**（multipart `file`，≤10MB）：解压定位 SKILL.md，技能名取 frontmatter `name`（回落包裹目录名/文件名），同名**覆盖**；含二进制/非 UTF-8 文件**拒绝整个包**（400）。响应 `{name, action, files, version}` |
| DELETE | `/skills/users/{userId}/{name}` | 删个人技能，**写 tombstone**（`/{name}/.deleted`），防沙箱内副本复活 |
| POST | `/skills/users/{userId}/{name}/sync-from-package` | 把包内基线下发为个人版：`{files, skipped, message}`（非 UTF-8 文件**跳过**并列入 `skipped`，与 zip 上传的「拒绝」语义不同） |

**回写仲裁（两个 KV 元数据键，命中即跳过同名技能）：**

| 标记 | 写入方 | 作用 |
|------|--------|------|
| `/{name}/.deleted` | 管理面删除 | 防删除被容器内副本复活 |
| `/{name}/.admin-override` | 管理面写入（PUT / 下发） | 防管理面写入被同代容器内旧副本改回 |

代价是标记生效期间，容器内 `skill_manage` 对该技能的修改不落库。状态与清除方式经
`GET /skills/users/{userId}` 的 `tombstones` 字段与删除 / PUT 响应下发。

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

工具列表。默认只返回 MCP 业务工具；`includeInternal=true` 时额外输出两段内置工具视图。

```bash
curl 'http://localhost:8100/tools'
curl 'http://localhost:8100/tools?includeInternal=true'
```

**query 参数：**

| 参数 | 默认 | 说明 |
|------|------|------|
| `includeInternal` | `false` | 是否输出自定义工具段与 SDK 内置段 |

**响应结构（三段并列）：**

```json
{
  "tools": [
    { "name": "get_weather", "server": "weather", "category": "mcp", "description": "Get weather" },
    { "name": "echo", "category": "internal", "source": "builtin", "declared": true }
  ],
  "totalCount": 2,
  "mcpCount": 1,
  "internalCount": 1,
  "sdkInternal": [ { "name": "read_file", "category": "sdk", "source": "sdk" } ],
  "sdkInternalCount": 1
}
```

**`tools[]` 字段：**

| 字段 | 说明 |
|------|------|
| `name` | 工具名（MCP 工具为远端裸名，不带 `mcp__{server}__` 前缀） |
| `category` | `mcp` / `internal` / `sdk`（**没有 `builtin` 这个取值**） |
| `server` | **仅 MCP 段有**：来源 server 名 |
| `source` | **仅内置段有**：`builtin`（`@Tool` 或插件）/ `sdk` |
| `declared` | **仅 internal 段有**：是否出现在 OAF `tools:` 声明列表中 |
| `description` | 工具描述 |
| `uiResourceUri` | MCP Apps（可选）：工具绑定的 `ui://` 资源 URI |
| `appOnly` | MCP Apps（可选，默认 false）：`ui.app_only: true` 的工具仅卡片展示 |

**计数口径（易踩坑）：**

| 字段 | 统计范围 |
|------|----------|
| `totalCount` / `mcpCount` / `internalCount` | **只统计 MCP + 自定义两段**（旧口径，为不破坏消费方保持不变） |
| `sdkInternalCount` | SDK 内置段规模——Harness 自注册工具通常有 20+ 个 |

- `includeInternal=false` 时，`internalCount=0`、`sdkInternal=[]`、`sdkInternalCount=0`（键恒存在，不会缺）。
- `sdkInternal` 是 **fail-soft**：Agent 未就绪或枚举异常时返回空列表，不让 `/tools` 失败。
- `deniedTools` 按类粒度剔除，会同时反映在 internal 与 sdkInternal 两段。

> **变更记录**：issue #39（2026-09-26）起 SDK 内置工具从 `tools[]` 拆到独立的 `sdkInternal[]`，
> 并把原 `category: builtin` 拆为 `internal` + `source`。消费方如按 `category == "builtin"` 过滤需改。
> 前端侧说明见 [api-frontend-sse.md](api-frontend-sse.md) §7.1。

---

### POST /admin/reload

OAF 配置动态 reload（docs/oaf-dynamic-reload-plan.md）。PVC /config 原位更新后免重启生效。

```bash
curl -X POST "http://localhost:8100/admin/reload?scope=auto"
curl -X POST "http://localhost:8100/admin/reload?scope=mcp&server=weather"
```

**Query 参数：**

| 参数 | 说明 |
|------|------|
| `scope` | `auto`（默认，指纹比对自动分流：仅 MCP 配置变 → 原地 reload；AGENTS.md 变 → 整包重建 agent）/ `mcp`（仅 MCP 原地 reload，重解析 frontmatter，声明增删即时生效；可加 `&server=<name>` 精准单 server）/ `agent`（强制整包重建，含 MCP 全量注册） |

**响应（200）：**

```json
{
  "scope": "mcp",
  "fingerprint_changed": true,
  "agent_rebuilt": false,
  "mcp_servers": [{"server": "weather", "action": "reloaded", "ok": true, "tool_count": 1}]
}
```

- `action`：`reloaded` / `removed`（声明已删除）/ `skipped`（fail-soft 注册失败）/ `registered`（整包重建路径）
- **失败（500）**：`{"scope", "error", "note": "old configuration remains active"}` —— 旧配置继续服务，修正后重新触发即可
- 生效语义：下一轮对话；进行中 turn 不打断；重复触发（指纹未变）返回 `scope=noop`

---

### GET /admin/reload

reload 只读状态（无副作用）。

```bash
curl http://localhost:8100/admin/reload
```

```json
{"scope": "status", "registeredServers": [{"server": "weather", "connected": true, "tool_count": 1}]}
```

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
        "user_id": "alice",
        "title": "销售数据分析",
        "model": "",
        "updated_at": "2026-08-21T10:00:00Z"
    }
]
```

> `title` 来源：手动重命名（PATCH）或**系统模型自动生成**（新会话首条消息后异步生成，已有标题不覆盖）；
> `model` 为会话绑定模型（`model_config.id`，空串 = 默认/系统模型），见 [模型 API](#模型-api会话可切换)。

---

### GET /threads/{sessionId}/history

Thread 历史消息 + pendingConfirm。

**查询参数**：

| 参数 | 默认 | 说明 |
|------|------|------|
| `includeArchived` | `true` | 归档合并开关；`false` 退回仅 agent_state 的现状行为 |
| `limit` | `200` | 归档合并视图分页条数；`<=0` 不分页 |
| `beforeId` | — | 归档行 id 游标（取该 id 之前更早的一页）；翻页只走归档行 |

**数据源**：归档开启（`AGENT_HISTORY_ARCHIVE_ENABLED`，默认 true）时为**双源合并视图**——
归档消息轨 `session_message`（append-only，AgentState 落库时 write-through，压缩前的原文在此）
按 id 升序为时间线基底，`agent_state` 当前上下文的未归档消息合入基底末尾；压缩摘要消息
（`__compaction_summary__`）渲染为 `role=compaction` 分隔条。**压缩不再吞噬历史**。
归档关闭或查询故障时自动回退仅 `agent_state`。

`agent_state`（官方 SDK 自动持久化的 AgentState）仍是当前上下文的事实来源——
工具调用带 `state`（ToolCallState：pending/asking/allowed/submitted/finished），
工具结果带 `state`（ToolResultState：success/error/denied/interrupted）与 `output`，
覆盖 HITL 批准后的恢复段，不受 Redis 事件流 TTL 限制。
`pendingConfirm` 优先取 state 中挂起的 ASKING 工具（`source=agent_state`，与 confirm_context
的 30 分钟 TTL 无关），无则回落 confirm_context（`source=confirm_context`，兼容老会话）。
详见 [history-agentstate-design.md](history-agentstate-design.md) 与
[session-history-archive-design.md](session-history-archive-design.md)。

```bash
curl http://localhost:8100/threads/acme-test-agent:thread-1/history
```

**响应:**

```json
{
    "session_id": "acme-test-agent:thread-1",
    "pendingConfirm": {
        "reply_id": "reply-001",
        "source": "agent_state",
        "tools": [
            {"tool_call_id": "uuid", "name": "submit_application", "input": {"action": "submit"}}
        ]
    },
    "messages": [
        {"role": "user", "content": "提交申请", "msg_id": "m1", "origin": "archive", "reply_id": "reply-001"},
        {"role": "compaction", "type": "compaction_summary", "content": "…压缩摘要…", "msg_id": "m2", "created_at": "…"},
        {"role": "assistant", "content": "请确认是否提交？", "msg_id": "m3", "origin": "archive",
         "tool_calls": [
            {"id": "call_1", "name": "list_images", "input": {},
             "state": "success", "output": "images: [...]"},
            {"id": "call_2", "name": "publish_service", "input": {"packageId": 3},
             "state": "asking"}
         ]}
    ],
    "hasMore": false,
    "nextBeforeId": null,
    "files": [
        {"file_id": "…", "file_name": "report.pdf", "mime_type": "application/pdf",
         "size": 102400, "reply_id": "reply-001", "download_url": "/files/…"}
    ]
}
```

**合并视图新增字段**（`messages[]`，存量会话仅 `origin=state` 或缺省）：

| 字段 | 说明 |
|------|------|
| `msg_id` | SDK Msg.id（toRoleContentList 透传；tool_calls 内层的 `id` 是工具调用 id，勿混淆） |
| `origin` | `archive`（来自消息轨归档，压缩前原文）/ `state`（来自 agent_state 当前上下文） |
| `role=compaction` 项 | 压缩分隔条合成项：`type=compaction_summary`，`content` 为摘要文本；老前端忽略即可 |
| `reply_id` | 仅 assistant 携带；来自 Redis 顺序回填（按首个事件 seq 逐轮分配）。归档不盖戳——实测 `AgentState.getReplyId()` 为会话级而非逐轮（见 session-history-archive-design.md §15） |
| `hasMore` / `nextBeforeId` | 归档分页游标；`nextBeforeId` 传回 `beforeId` 取更早一页 |

> **`files[]` 是本轮新增同步的字段**：产出文件（`present_file` / `present_url`）在历史回放时经
> `file_asset` 表关联本会话，供前端补渲染下载卡片。

**工具结果字段**（`tool_calls[]`）：

| 字段 | 说明 |
|------|------|
| `state` | 结果状态（`success`/`error`/`denied`/`interrupted`）优先；无结果时回落 ToolCallState（如 `asking`） |
| `output` | 工具结果文本；**敏感值已遮掩**（密钥键值对 / `tp-` token / Bearer），且已按上限截断 |
| `output_truncated` | 可选：`true` 表示因超限被截断 |
| `output_full_length` | 可选：截断前的完整字符数 |

**有序块序列**（assistant 消息可选字段 `blocks`）：

`content` 是所有 text 块的拼接、`tool_calls` 是工具块的集合——两者是平行提取，彼此先后已丢。
`blocks` 按 `Msg.content` 原始块序补回该信息，供 Debug Console 按**真实发生顺序**渲染
（开场白留在它那批工具步骤的上方）；元素形如：

| 元素 | 字段 | 说明 |
|------|------|------|
| `{"type":"text","text":"…"}` | `text` | 一个 text 块的原文 |
| `{"type":"tool","id":"…","name":"…"}` | `id`/`name` | 一个 tool_use 块；`state`/`output` 等结果字段仍从 `tool_calls[]` 按 id 认领 |

- thinking 块不上屏、tool_result 块不进 `blocks`（结果仍由 `tool_calls[]` 承载）
- 仅 assistant 消息携带；content 非数组（旧格式字符串）时不写该键，前端退回「工具组在上、文本在下」旧布局
- 既有键（`content`/`tool_calls`/`msg_id`/…）不受影响

截断上限由 `AGENT_HISTORY_TOOL_OUTPUT_MAX_CHARS` 控制（默认 8000，`<=0` 关闭）。

---

### GET /threads/{sessionId}

会话详情：`session_id` / `user_id` / `model` / `updated_at` / `pendingConfirm` / `files` / `messages`。
结构与 `GET /threads/{sessionId}/history` 基本一致，额外带 `user_id`、`model`、`updated_at`；
messages 固定为归档合并视图首页（翻页走 history 的 `beforeId`）。

```bash
curl http://localhost:8100/threads/acme-test-agent:thread-1
```

---

### PATCH /threads/{sessionId}

重命名会话 / 切换会话级模型。**两个字段都可选**，只传其一合法。

```bash
curl -X PATCH "http://localhost:8100/threads/acme-test-agent:thread-1" \
  -H 'Content-Type: application/json' \
  -d '{"title":"新的会话标题","model":"my-model-id"}'
```

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `title` | String | | 会话标题；**自动生成后也可在此手动改** |
| `model` | String | | 会话绑定模型（`model_config.id`）；`""` / `"system"` 清除覆盖回默认 |

**响应：** `{"session_id": "...", "title": "...", "model": "..."}`

---

### DELETE /threads/{sessionId}

删除会话。级联清理 `agent_state` / `agent_fs` / `session_user` / `turn_lease` /
`confirm_context` / `file_asset` 等表，以及 Redis 中 `sess:{sid}:events` 与 `sess:{sid}:replies` 两把 key。

```bash
curl -X DELETE "http://localhost:8100/threads/acme-test-agent:thread-1"
```

**响应：** `{"session_id": "...", "deleted": true, "rows_affected": N}`

---

### GET /threads/{sessionId}/llm-calls

LLM 调用记录（进程内存，非持久化；重启即丢）。

```bash
curl http://localhost:8100/threads/acme-test-agent:thread-1/llm-calls
```

**响应：** `{"session_id": "...", "calls": [{"call_id": "...", "timestamp": "...", "request": {...}, "response": {...}}]}`

**记录键口径**：`sessionId` 取规范会话 id（= `session_user.session_id`，与 `GET /threads` 列表、
history/PATCH/DELETE 同键），由 `SessionKeyResolver` 从 RuntimeContext 反查解析——`sessionId` 与
`userId` 两个候选中，谁在 session_user 登记过谁就是规范 sid。注意 Channel 链路
（`/threads/chat`）下 `RuntimeContext.sessionId` 是网关按 canonicalKey 派生的 `gw-hash`
（issue #87 起 Channel 切 `ChatUiChannel.perPeer()` 后**每会话独立**，此前 MAIN 形态为全进程
共享 `gw-3f20f08c5499`，存量行由 Flyway V9 重键），**不是任何会话的规范 key**，前端 sid 落在
`RuntimeContext.userId`；若直接用 `ctx.getSessionId()` 落记录，会话记录落在 gw-hash 桶、
按 sid 查询恒为空（issue #44）。

---

## 无状态单次流 SSE API

### POST /threads/chat

单次流 SSE 对话端点（**唯一对话入口**）。sessionId 在请求体中、可选：不传则自动生成 UUID（首个 SSE 事件为 `session_created`），传了则续接已有会话。每次请求抢 Turn 租约（排队语义）→ Agent 执行 → 事件经 SessionEventBus 持久化 + 广播 → AGENT_END/error 帧关闭流、释放租约。

```bash
curl -s -N -X POST "http://localhost:8100/threads/chat" \
  -H 'Content-Type: application/json' \
  -d '{"message":"hello","userId":"alice","sessionId":"acme-test-agent:thread-1"}'
```

**请求体:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `message` | String | ✓* | 用户消息（*与 `fileIds` 至少一项） |
| `userId` | String | | 用户标识（默认 `debug-user`；`X-User-Id` Header 优先） |
| `sessionId` | String | | 会话 ID；**省略时自动生成 UUID** |
| `fileIds` | List\<String\> | | 随消息上传的文件 ID 列表（先经 `POST /files/upload` 上传；注入会话工作区，图片内联为 ImageBlock，见 [file-upload-download-plan.md](file-upload-download-plan.md)） |
| `model` | String | | 会话模型（`GET /models` 的 id；**缺省=不改变绑定**，传值即绑定本会话并本 turn 生效，`""`/`system`=回到默认模型；未知/禁用返回 error 帧 `unknown_model: xxx`） |

**响应 (SSE):**

```
data: {"type":"session_created","session_id":"..."}   ← 仅当请求未传 sessionId
data: {"type":"waiting"}                    ← 排队等待时每 15s 一帧（防 Nginx 读超时）
data: {"type":"TEXT_BLOCK_DELTA","delta":"Hello","replyId":"...","blockId":"..."}
data: {"type":"AGENT_END","replyId":"..."}
```

**SSE 事件类型:**

| type | 说明 |
|------|------|
| `session_created` | 新会话创建（仅当请求未传 `sessionId`，作为首个事件下发） |
| `waiting` | 排队等待（同 session 有活跃 turn 时，每 15s 一帧） |
| `TEXT_BLOCK_DELTA` | 文本 token（流式累加） |
| `THINKING_BLOCK_DELTA` | 深度思考内容增量（`LLM_ENABLE_THINKING=true` 时出现） |
| `MODEL_CALL_END` | 模型调用结束，携带 `inputTokens`/`outputTokens`/`totalTokens`（usage 为 null 时三字段缺省） |
| `TOOL_CALL_START` | 工具调用开始（MCP Apps 工具携带 `ui` 元数据） |
| `TOOL_RESULT_END` | 工具返回结果，`state` ∈ `SUCCESS`/`ERROR`/`INTERRUPTED`/`DENIED`/`RUNNING` |
| `tool_call_summary` | **合成帧**：工具调用中文摘要（`summary`/`toolCallId`/`toolName`），HITL 恢复段兜底补发 |
| `tool_result_preview` | **合成帧**：工具结果预览（`preview`/`toolCallId`/`toolName`） |
| `permission_ask` | HITL 暂停点（需人工确认），携带 `tool_calls[]` 与 `reply_id` |
| `file_ready` | `present_file` / `present_url` 工具产物就绪（含 `file_id`/`file_name`/`mime_type`/`size`/`download_url`，前端渲染下载卡片） |
| `AGENT_RESULT` | 最终结果聚合（实测于 HITL 恢复段末尾，`AGENT_END` 之前） |
| `USER_CONFIRM_RESULT` | HITL 确认结果落地（`confirm-stream` 恢复段首个业务事件） |
| `AGENT_START` / `AGENT_END` | 执行段起止；**`AGENT_END` 之后流直接关闭** |
| `subagent_exposed` | 子 agent 调用收口（`AgentSpawnTool` 结果返回时发出，snake_case 四字段） |
| `error` | 错误（如 `turn_in_progress` 排队超时、`turn_pending_confirm`、`unknown_model`） |
| `done` | **仅由 `/threads/{sid}/subscribe` 补发**——`POST /threads/chat` 不发此帧 |
| `interrupted` | **仅由 `/subscribe` 补发**：`{"type":"interrupted","reason":"turn_interrupted"}` |

> **`data` 里没有 `seq` 字段。** 游标在 SSE `id:` 行上（`EnvelopedEvent.seq()`）；
> `data.id` 是 SDK 事件 ID，两者不是一回事。控制帧与心跳 `:hb` 没有 `id:` 行。
>
> **远程转发标注：** 远程子 agent 事件回流 lead SSE 时 additive 附 `source` / `taskId` /
> `parentSessionId`（lead 自身事件无 `source`）；转发的 `AGENT_END` 不终结主 turn。

**时序约束：**
- 单次 POST 即发起完整执行段，无需先建立 SSE 订阅
- 同 session 并发请求自动排队（Turn 租约），排队超时 120s 返回 error 帧
- HITL 暂停点：挂起态落 `agent_state`（`confirm_context` 仅兜底），释放 Turn 租约，流关闭；恢复走 `confirm-stream`
- `permission_ask` 挂起期间发新 turn 被 `/chat` 入口 ASKING 预检拒绝（error 帧 `turn_pending_confirm`），需先 confirm

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

> **恢复段的合成帧**：HITL 确认恢复后，SDK 不会重放 `TOOL_CALL_*` 事件，框架按 `toolCallId`
> 在 `RESULT_END` 时补发 `tool_call_summary`（形如「执行 submit_report」）与 `tool_result_preview`，
> 保证前端工具气泡有可展示的摘要。语义见 [hitl-tool-summary-recovery-design.md](hitl-tool-summary-recovery-design.md)。

---

## 观察者 API（断线重连 / 状态判定）

### GET /threads/{sessionId}/subscribe

**只读**观察端点：不执行 Agent，只从 Redis Stream + MySQL 租约读事件并推送。
因此可以在任意副本上订阅——**换 Pod 重连照样能拿到后续事件**。

```bash
curl -sN -G "http://localhost:8100/threads/acme-test-agent:thread-1/subscribe" \
  --data-urlencode "afterSeq=42" \
  --data-urlencode "replyId=reply-001" \
  -H 'Accept: text/event-stream'
```

**query 参数：**

| 参数 | 必填 | 说明 |
|------|------|------|
| `afterSeq` | | 游标，回放**严格大于**该 seq 的事件；缺省 0 = 从头回放整个会话 |
| `replyId` | | 只跟某一个 turn（经 Redis ZSET `sess:{sid}:replies` 过滤） |

> ⚠️ **不读 `Last-Event-ID` 请求头。** 游标只能显式放 query。浏览器原生 `EventSource` 的自动重连
> 对本端点无效，必须用 `fetch` + `ReadableStream`，或自建 EventSource 并把最后的 SSE `id:` 拼进 `afterSeq`。

**SSE 帧：** 每帧 `id:` = seq；空闲时发 `:hb` 心跳 comment（间隔 `AGENT_SSE_HEARTBEAT_SECONDS`，默认 20s）；
追到终态后补 `{"type":"done"}`；执行副本崩溃时补 `{"type":"interrupted","reason":"turn_interrupted"}` 后关闭流。

**终态类型：** `AGENT_END` / `error`（以及 `permission_ask`——挂起等确认也算本轮结束）。

---

### GET /threads/{sessionId}/status

刷新恢复的判态端点。

```bash
curl http://localhost:8100/threads/acme-test-agent:thread-1/status
```

**响应:**

```json
{
  "session_id": "acme-test-agent:thread-1",
  "state": "completed",
  "latest_event_seq": 42,
  "reply_id": "reply-001",
  "pending_confirm": null
}
```

**`state` 取值（5 态）：**

| state | 含义 | 前端动作 |
|------|------|----------|
| `idle` | 无事件、无租约、无待确认 | 显示空白会话 |
| `working` | 有活跃租约，正在执行 | 走 `/subscribe?afterSeq={latest_event_seq}` |
| `waiting_confirm` | 挂起等待人工确认 | 渲染确认卡（可用 `pending_confirm` 重建） |
| `completed` | 正常终态 | 走历史回放 |
| `interrupted` | 有事件但**无租约、无待确认**——执行副本崩溃或被抢占 | 展示中断态并允许重发 |

> **`interrupted` 是 2026-09 起新增的第 5 态**（此前文档只写 4 态）。

**503 语义：** 事件存储不可用时返回 `503 {"error":"event_store_unavailable"}`。
**前端必须保留本地游标、不得重置为 0**——否则会触发整场重放。MySQL / Redis 任一不可用都会显式报错，
而不是假装 idle。

---

## 文件 API（file-upload-download-plan）

### POST /files/upload

multipart 单文件上传。校验（文件名 sanitize / MIME 白名单 / 大小上限 / pending 数量上限）→ 先写存储后端 → 落 `file_asset` 元数据（落库失败回滚存储对象）。非沙箱模式直接 `injected`；沙箱模式 `pending` 挂账，首次 exec 时注入沙箱。

```bash
curl -X POST "http://localhost:8100/files/upload" \
  -F "file=@report.pdf" -F "userId=alice" -F "sessionId=acme-test-agent:thread-1"
```

| 表单字段 | 必填 | 说明 |
|----------|------|------|
| `file` | ✓ | 单文件（v1） |
| `userId` | | 默认 `debug-user`（沙箱注入命名空间） |
| `sessionId` | | 上传时绑定会话 |

**响应:**

```json
{"file_id": "uuid", "file_name": "report.pdf", "mime_type": "application/pdf", "size": 10240}
```

**错误码:** 403 `upload_disabled`、400 `no_file_uploaded`/`invalid_file_name`、415 `unsupported_file_type`、413 `file_too_large`、429 `too_many_pending_files`、500 `storage_write_failed`/`metadata_write_failed`。

### GET /files/{fileId}

下载/预览（`?inline=1` 时仅 image/*、text/* 内联展示；平台无认证，UUID 不可枚举即授权）。

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

### tasks/resubscribe

重新订阅任务事件流（SDK 透传）。

---

## A2A 幂等 Job REST（member 侧，默认关闭）

`POST /a2a/jobs` + `GET /a2a/jobs/{key}`（`A2aJobController`，`agent.a2a-job.enabled=true` 时才装配；
token 经 `Agent-A2A-Job-Token` 头认证，缺失/错误一律 401）。幂等状态机全外置 Redis 单键原子操作，
发送走 loopback `message/send`；409 = 同键在途/结果未知（持同键重试直至收敛），503 = 并发准入饱和 /
Redis 故障（fail-closed）。详见 [travel-fulfillment-agent-protocol-design.md](../../docs/design/travel-fulfillment-agent-protocol-design.md) §18。

---

## 模型 API（会话可切换）

系统模型（`LLM_*` 环境变量，只读，即默认模型）+ 托管模型（`model_config` 表，CRUD）统一暴露；
会话模型切换的完整设计见 [session-model-switch-design.md](session-model-switch-design.md)。
debug 页「🤖 Models」模块为管理界面。

### GET /models

可选模型列表（前端 picker / 管理页数据源；`?all=true` 含禁用的托管模型）。

```bash
curl "http://localhost:8100/models"
```

**响应:**

```json
{
  "default_model": "system",
  "models": [
    {"id": "system", "name": "qwen3-32b（系统）", "provider": "openai", "model_id": "qwen3-32b",
     "is_default": true, "source": "system", "enabled": true},
    {"id": "3f1c...", "name": "DeepSeek-V3", "provider": "openai", "model_id": "deepseek-v3",
     "is_default": false, "source": "managed", "enabled": true}
  ]
}
```

> 上例为节选；完整视图另含 `base_url` / `api_key_masked` / `temperature` / `max_tokens` /
> `timeout_seconds` / `enable_thinking` / `reasoning_effort` / `frequency_penalty` /
> `context_length` / `read_only`（系统模型 true）等字段（V4 起含采样参数两列）。

### GET /models/{id}

模型详情（托管模型含 `api_key_masked` 掩码，不返回明文；系统模型 `read_only=true`）。404 `model_not_found`。

### POST /models

新增托管模型。`name`/`modelId`/`baseUrl` 必填；`apiKey` 留空/null = 复用系统 `LLM_API_KEY`。

```bash
curl -X POST http://localhost:8100/models -H 'Content-Type: application/json' -d '{
  "name": "DeepSeek-V3", "modelId": "deepseek-v3", "baseUrl": "https://api.example.com/v1",
  "apiKey": "<占位符>", "temperature": 0.3, "maxTokens": 16384, "enabled": true
}'
```

**响应:** 托管模型详情（含 `id`，掩码 key）。错误：`400 invalid_config` / `400 duplicate_name`。

### PATCH /models/{id}

局部更新：字段缺省=不变；`apiKey=""`=清空回落系统密钥；`reasoningEffort=""`=清除不下发；
`frequencyPenalty` 范围 [-2, 2]；系统模型 → `400 system_model_readonly`。

### DELETE /models/{id}

删除托管模型。引用它的会话在下次模型调用时**回落默认模型**（上下文/历史不受影响），后续新请求可切换到其他模型。
系统模型 → 400；不存在 → 404。

### POST /models/{id}/test

连接测试：真实发起一次最小 completion（max_tokens=16）。

**响应（成功）:** `{"id": "...", "ok": true, "latency_ms": 812, "reply": "pong"}`
**响应（失败）:** `502` + `{"id": "...", "ok": false, "latency_ms": 120, "error": "model_test_failed", "message": "<上游错误摘要>"}`

---

## 调试 API（/debug）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/debug` | 调试页面（静态资源） |
| GET | `/debug/config/env` | 生效环境变量（脱敏）。`llm.fallback_model_id` = 备用模型配置（`LLM_FALLBACK_MODEL_ID`，空串 = 未启用） |
| GET | `/debug/config/oaf` | OAF 配置 + 技能合并视图（dynamic/declaredButMissing 标记） |
| GET | `/debug/database/status` | 数据库连接健康状态 |
| GET | `/debug/memory` | MEMORY.md / memory/ 内容查看 |
| GET | `/debug/sandbox` | 沙箱状态（沙箱模式） |
| GET | `/debug/workspace` | 工作区文件浏览 |
| GET | `/debug/logs` | 运行日志（内存 Appender）。query `level`（默认 all）、`limit`（默认 100，上限 500） |
| GET | `/debug/user-skills` | 用户个人技能（L4）索引：`{count, users[], truncated?}` |

> `/debug/*` 与 `/admin/reload` **均无鉴权**，部署时依赖集群内网入口 / ingress 保护。

### GET /actuator/health

Spring Boot Actuator 健康检查。`pom.xml` 引入了 actuator，但 `application.yml` **没有 `management:` 段**，
因此走默认配置——**只暴露 `health`**，其余（`/actuator/info`、`/actuator/env`、`/actuator/metrics`）均未开放。

```bash
curl http://localhost:8100/actuator/health
```

> `/health`（自定义端点）恒返回 `status: "healthy"`，**`llm_configured: false` 不会降级为 unhealthy**。
> 探活脚本需自行判断该字段。

---

## 数据库表（无状态单次流架构）

自建 10 张表，表结构由 **Flyway 迁移**管理（`src/main/resources/db/migration/` V1–V9，启动自动执行，
存量库自动 baseline；演进一律新增迁移文件，代码内不再手工 DDL）：`confirm_context` / `turn_lease` /
`tool_audit_log` / `ui_context` / `file_asset` / `kv_sync_key` / `model_config` / `session_user` /
`session_message`（V5，消息轨归档）/ `remote_task_registry`（V8，远程子任务在途登记）。
设计与规范见 [db-migration-flyway-design.md](../../docs/design/db-migration-flyway-design.md)。

事件流（`session_event`）已整体迁至 Redis Streams，见本节末。SDK 侧的
`agent_state` / `agent_fs` 表结构见 [checkpoint-design.md](checkpoint-design.md)。

### confirm_context

HITL 确认上下文（人工确认场景跨副本持久化）。**V7 起多行化**：主键 `(session_id, confirm_key)`——
本地 HITL 行 `confirm_key='local'`（同 session 覆盖写，语义不变）；远程确认行 `confirm_key='task:{task_id}'`
（同 session 多行 FIFO 排队，带 `remote_task` 锚点 JSON，独立 TTL 默认 24h）。

| 列 | 类型 | 说明 |
|----|------|------|
| `session_id` | VARCHAR(255) PK 部分 | 会话 key |
| `confirm_key` | VARCHAR(191) PK 部分 | `local`（本地行）/ `task:{task_id}`（远程确认行，V7） |
| `tool_calls_json` | MEDIUMTEXT | 待确认工具调用列表（`[{id, name, input}]`） |
| `remote_task` | MEDIUMTEXT NULL | 远程行锚点 JSON（`{service, task_id, tool_calls, child_reply_id}`），本地行 NULL（V7） |
| `reply_id` | VARCHAR(64) | 触发确认的 reply 标识 |
| `runtime_session_id` | VARCHAR(255) | Channel 流程网关推导的真实 sessionId |
| `runtime_user_id` | VARCHAR(255) | Channel 流程网关推导的真实 userId |
| `created_at` | DATETIME(3) | 创建时间（TTL 懒判断依据） |
| `consumed` | TINYINT(1) | 0=待确认，1=已消费（CAS 0→1 防重复） |

**TTL:** 本地行默认 30 分钟（`confirmTtlMinutes`），远程行默认 24h（按 `confirm_key` 分档判定），
读时懒判断 + 定时清理兜底。

**定位（2026-09 起）：兼容兜底，不再是 HITL 恢复的权威来源。**
挂起态的权威来源是 `agent_state` 里 SDK 持久化的 ASKING 工具（无 TTL，
见 [history-agentstate-design.md](history-agentstate-design.md)）；
本表用于老会话回落与状态查询。state 路径恢复成功时会把残留行顺手消费，
避免二次提交走到陈旧上下文。

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

**保护范围：** `/threads/chat`、`/threads/{sid}/confirm-stream`、`/threads/{sid}/confirm`
（同步版于 2026-09-18 补齐，此前无租约——多副本下并发确认会重复执行；抢不到返回
409 `turn_in_progress`）。

### model_config

托管模型配置（会话可切换模型的配置来源，见 [session-model-switch-design.md](session-model-switch-design.md)）。
Flyway V3 建表、V4 补采样参数列；`api_key` 为**明文列**（内网库，读接口一律掩码返回）。

| 列 | 类型 | 说明 |
|----|------|------|
| `id` | VARCHAR(64) PK | UUID；会话按此 id 引用（改名不断链） |
| `name` | VARCHAR(128) 唯一 | 展示名 |
| `provider` | VARCHAR(32) | 推理引擎方言（openai / vllm / sglang / glm / deepseek，V4 起枚举校验） |
| `model_id` | VARCHAR(128) | 发给推理端点的模型名 |
| `base_url` | VARCHAR(512) | OpenAI 兼容端点 |
| `api_key` | VARCHAR(512) NULL | NULL = 回落系统 `LLM_API_KEY` |
| `temperature` / `max_tokens` / `timeout_seconds` | DOUBLE / INT / INT | 采样与超时（默认 0.3 / 16384 / 120） |
| `enable_thinking` | TINYINT(1) | false 时按 provider 方言注入关闭参数（`ChatModelFactory.applyDialect` 单点收口：vllm/sglang 走 `chat_template_kwargs`、glm 走 `thinking.type`、openai 兜底走顶层一等字段、deepseek 不下发） |
| `reasoning_effort` | VARCHAR(16) NULL | 推理强度（V4；NULL=不下发，空串清除） |
| `frequency_penalty` | DOUBLE NULL | 频率惩罚 [-2, 2]（V4；NULL=不下发） |
| `context_length` | INT | >0 才传给模型 |
| `enabled` | TINYINT(1) | 会话可选开关 |
| `created_at` / `updated_at` | DATETIME(3) | — |

### session_user（会话模型列）

`session_user` 除 `remark`（标题）外有 `model VARCHAR(128) DEFAULT ''`（Flyway V3 迁移添加）：
空串 = 默认（系统）模型；非空 = 绑定 `model_config.id`。`SessionModelMiddleware` 每次模型调用实时读该列，
多副本下 PATCH/chat 落库即生效（无进程内缓存）。

### session_message（消息轨归档）

会话消息轨 append-only 归档（Flyway V5，`SessionMessageStore` write-through）：AgentState 落库时
同步写压缩前原文，history 双源合并视图以此为主时间线基底（见上文 `/history`）。清理随
`AGENT_CLEANUP_SESSION_RETENTION_DAYS`（`SessionCleanupService`）。

### remote_task_registry（远程在途登记）

lead 侧远程子任务在途登记（Flyway V8，travel-fulfillment 设计 §18.2）：spawn 登记行
（`(session_id, task_id)` 唯一），`status` 状态机 `IN_FLIGHT → TERMINAL / GIVEN_UP`；多副本下
重建轮询与唤醒幂等的调度簿（confirm_context 仍是唯一授权事实源）。终态行保留 7 天。

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

### ui_context

MCP Apps 静默上下文（卡片与 LLM 共享的中间态，见 [mcp-apps-extension-plan.md](mcp-apps-extension-plan.md)）。

| 列 | 类型 | 说明 |
|----|------|------|
| `session_id` | VARCHAR(255) PK | 会话 key |
| `tool_call_id` | VARCHAR(128) | 关联的工具调用 |
| `content_json` | MEDIUMTEXT | 卡片回传的结构化内容 |
| `updated_at` | DATETIME(3) | 更新时间 |

---

### file_asset

文件资产元数据（`present_file` / `present_url` 产物与上传文件的统一台账，见 [file-upload-download-plan.md](file-upload-download-plan.md)）。

| 列 | 类型 | 说明 |
|----|------|------|
| `file_id` | VARCHAR(64) PK | 资产 ID（UUID） |
| `session_id` / `reply_id` | VARCHAR | 关联会话与 turn（Channel 链路下为 `gw-<hash>`） |
| `file_name` / `mime_type` / `size` | — | 文件元信息 |
| `storage_type` | VARCHAR | `local` / `s3` / `external` |
| `created_at` | DATETIME(3) | 创建时间（`FILE_RETENTION_DAYS` 保留期依据） |

---

### kv_sync_key

KV 同步去重键（沙箱 `WorkspaceSyncService` 回写时避免重复写，见 [opensandbox-integration-plan.md](opensandbox-integration-plan.md)）。

| 列 | 类型 | 说明 |
|----|------|------|
| `sync_key` | VARCHAR(255) PK | 去重键 |
| `updated_at` | DATETIME(3) | 更新时间 |

---

### session_event（事件流，已迁 Redis）

`session_event` 的读写整体迁至 **Redis Streams**（`service/RedisEventLog.java`），MySQL 侧仅保留迁移期结构。
事件流事实来源为 Redis：`sess:{sid}:events`（Stream）与 `sess:{sid}:replies`（ZSET，按 turn 分组），
TTL 7 天（自最后写入起），`AGENT_REDIS_MAX_LEN_PER_STREAM` 默认 25 万条兜底。

| 环境变量 | 默认 | 说明 |
|----------|------|------|
| `AGENT_REDIS_URL` | `redis://127.0.0.1:6379` | **集群必配**；不配则事件不落库，回放/续传全失效且不报错 |
| `AGENT_REDIS_COMMAND_TIMEOUT_MS` | `2000` | Lettuce 默认关闭命令超时，不设会占住 Tomcat 线程 |
| `AGENT_REDIS_CONNECT_TIMEOUT_MS` | `2000` | |
| `AGENT_REDIS_MAX_LEN_PER_STREAM` | `250000` | `XADD … MAXLEN ~` 内存兜底 |

详见 [api-frontend-sse.md](api-frontend-sse.md) §14 与 [checkpoint-design.md](checkpoint-design.md) §4.1。

---

## 清理配置

清理参数通过 `CleanupConfig`（环境变量前缀 `AGENT_CLEANUP_*`）配置。

> ⚠️ **`application.yml` 里没有 `agent.cleanup` 段**，这些变量靠 Spring 的 `@ConfigurationProperties`
> 松弛绑定 + `@DefaultValue` 生效。查默认值请看 `config/AgentManagerProperties.java` 的 `CleanupConfig`，
> 而不是 `application.yml`。

| 环境变量 | 默认值 | 说明 |
|---------|--------|------|
| `AGENT_CLEANUP_CONFIRM_TTL_MINUTES` | `30` | confirm_context 有效时长（分钟） |
| `AGENT_CLEANUP_TURN_LEASE_TTL_SECONDS` | `60` | turn_lease 租约 TTL（秒） |
| `AGENT_CLEANUP_TURN_LEASE_RENEW_SECONDS` | `20` | turn 续租间隔（秒） |
| `AGENT_CLEANUP_AUDIT_RETENTION_DAYS` | `30` | tool_audit_log 保留天数 |
| `AGENT_CLEANUP_SESSION_RETENTION_DAYS` | `7` | agent_state/agent_fs 保留天数 |
| `AGENT_HISTORY_TOOL_OUTPUT_MAX_CHARS` | `8000` | history 工具输出截断上限（`<=0` 不截断） |

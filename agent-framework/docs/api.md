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
        "metadata": "/metadata"
    },
    "engine": "AgentScope Java 2.0"
}
```

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

## AG-UI 对话 API（agui-migration-plan，主对话入口）

### POST /agui/run（及 /agui/run/agent/{agentId}/run 双路由）

AG-UI 标准协议单次流 SSE：`RunAgentInput`（JSON body）→ `RUN_STARTED`…`RUN_FINISHED` 事件帧。
输入裁剪（D7/要点 2）→ Turn 租约排队（`oaf.waiting` CUSTOM 心跳）→ adapter 执行 → 终态释放。
HITL：工具调用被 ASK 拦截时 `RUN_FINISHED` outcome=`interrupt`，上下文落 `agui_interrupt` 表；
恢复 = 新 run 携带顶层 `resume[]`（`[{interruptId, status, payload:{approved}}]`，必须覆盖全部挂起 interrupts）。

配套 REST（CopilotKit 契约，R7 spike 定稿）：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/agui/run/info` | agents 对象 map + capabilities（agent 标识取 OAF 包 agentKey） |
| GET | `/agui/run/threads?agentId=&limit=` | 会话列表（archived=旧复合 key 只读会话，D5） |
| GET | `/agui/run/threads/{id}/messages` | 消息 + pendingInterrupts（R11 刷新兜底数据源） |
| POST | `/agui/run/agent/{agentId}/connect` | 客户端探测，恒 204 |
| POST | `/agui/run/agent/{agentId}/stop/{threadId}` | 中断在跑执行段 |

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

## 调试 API（/debug）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/debug` | 调试页面（静态资源） |
| GET | `/debug/config/env` | 生效环境变量（脱敏） |
| GET | `/debug/config/oaf` | OAF 配置 + 技能合并视图（dynamic/declaredButMissing 标记） |
| GET | `/debug/database/status` | 数据库连接健康状态 |
| GET | `/debug/memory` | MEMORY.md / memory/ 内容查看 |
| GET | `/debug/sandbox` | 沙箱状态（沙箱模式） |
| GET | `/debug/workspace` | 工作区文件浏览 |
| GET | `/debug/logs` | 运行日志（内存 Appender） |

---

## 数据库表（无状态单次流架构）

无状态单次流架构引入的表，服务启动时自动建表（幂等）；confirm_context 随旧 HITL 链路退役（agui-migration-plan Phase 3，HITL 由 agui_interrupt 承接）。

### turn_lease

Turn 租约（同一 session 执行段串行化）。Token + 短 TTL + 续租，崩溃由 TTL 过期兜底。

| 列 | 类型 | 说明 |
|----|------|------|
| `session_id` | VARCHAR(255) PK | 会话 key |
| `token` | CHAR(36) | 租约 token（UUID，release/renew 时校验防误删） |
| `expires_at` | DATETIME(3) | 过期时间 |
| `created_at` | DATETIME(3) | 创建时间 |

**TTL:** 默认 60 秒（`turnLeaseTtlSeconds`），续租间隔默认 20 秒（`turnLeaseRenewSeconds`）。

**租约语义：** 只覆盖活跃执行段。AG-UI RUN_FINISHED(interrupt)（HITL 暂停点）即让出锁；resume[] 恢复 = 新执行段需重新 acquire。

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

### agui_interrupt

AG-UI HITL 挂起中断上下文（跨进程/跨副本 resume）。Thread 粒度覆盖写，CAS 防重复消费。

| 列 | 类型 | 说明 |
|----|------|------|
| `thread_id` | VARCHAR(255) PK | 会话 key（AG-UI 纯 threadId，D5） |
| `interrupts_json` | MEDIUMTEXT | 挂起 `AguiEvent.Interrupt[]`（含 metadata toolName/toolContent/interruptKind） |
| `run_id` | VARCHAR(128) | 触发挂起的 runId |
| `consumed` | TINYINT(1) | 0=挂起，1=已消费（CAS 0→1） |
| `created_at` / `updated_at` | DATETIME | TTL 判断依据 |

**TTL:** 默认 30 分钟，读时懒判断 + SessionCleanupService 定时清理兜底。

## 清理配置

无状态单次流架构清理参数通过 `CleanupConfig`（环境变量前缀 `AGENT_CLEANUP_*`）配置：

| 环境变量 | 默认值 | 说明 |
|---------|--------|------|
| `AGENT_CLEANUP_TURN_LEASE_TTL_SECONDS` | `60` | turn_lease 租约 TTL（秒） |
| `AGENT_CLEANUP_TURN_LEASE_RENEW_SECONDS` | `20` | turn 续租间隔（秒） |
| `AGENT_CLEANUP_AUDIT_RETENTION_DAYS` | `30` | tool_audit_log 保留天数 |
| `AGENT_CLEANUP_SESSION_RETENTION_DAYS` | `7` | agent_state/agent_fs 保留天数 |

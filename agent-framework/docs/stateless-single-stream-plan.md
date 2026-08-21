# 无状态单次流架构改造设计（stateless-single-stream-plan）

> 状态：**设计定稿**（决策 O1-O7 已关闭；剩余风险 R3/R12，均由 SPIKE S2 验证收敛）
> 范围：agent-framework（AgentScope Java 2.0.0 + Spring Boot 3.3，端口 8100）
> 目标部署形态：单 Agent 多副本（无状态水平扩展）

---

## 1. 背景与目标

### 1.1 问题

当前长连接事件总线（`SessionEventBus`）为**进程内**实现（`ConcurrentHashMap` + `Sinks.Many` 分桶广播），多副本部署时：

1. `POST /threads/{sid}/chat`（fire-and-forget 触发）与 `GET /threads/{sid}/events`（SSE 订阅）若被 LB 分发到不同 Pod，事件无法跨 Pod 扇出，静默丢失；
2. 事件不持久化、无重放，Pod 漂移/重启后断流；
3. HITL 确认上下文（`AgentRuntimeService.confirmCache`）同样是进程内缓存，跨副本确认必然 404。

### 1.2 目标

- **放弃长连接**：对话改为 `POST /chat` 单次流（SSE 直吐，执行完即关闭），服务端零长连接状态，任意请求可落任意副本；
- **HITL 暂停持久化**：人工确认场景下将确认上下文落库（跨副本可见），人工决策完成后以决策结果重开流（`confirm-stream` 单次流）；
- **刷新重建 UI 走会话历史/状态**：不引入事件回放接口与全量事件表；
- **轻量审计**：仅工具调用类事件落库（异步批量，零主路径阻塞）。

### 1.3 非目标

- 不做跨副本事件实时扇出（Redis pub/sub / DB 轮询均不引入）；
- 不做全量事件日志（含文本 delta）的审计/回放；
- 不动 A2A 协议路径（`POST /` JSON-RPC，标准协议自带 resubscribe 语义）；
- 不动旧 `StreamController`（`GET /chat/stream`），保留兼容。

---

## 2. 现状盘点

| 能力 | 现状 | 处置 |
|------|------|------|
| 对话触发 | `POST /threads/{sid}/chat` fire-and-forget，事件经 `SessionEventBus` 回流到长连接订阅 | **改为 SSE 单次流直吐** |
| 事件订阅 | `GET /threads/{sid}/events` 长连接 SSE + 15s 心跳 | **删除** |
| 单次流雏形 | 旧 `GET /chat/stream`（StreamController，一次性 Channel 流），前端 `sendChannelSingleStream` 已使用 | 保留不动 |
| HITL 确认流 | `POST /threads/{sid}/confirm-stream`（单次流，词表一致）+ `/confirm`（同步版） | 保留，上下文来源改造 |
| 确认上下文 | 进程内 `confirmCache`（ConcurrentHashMap，30min TTL，CAS consumed） | **落库**（confirm_context 表） |
| 会话历史 | `GET /threads/{sid}/history`（agent_state.state_data 解析 context[]，块级消息含 tool_calls） | 复用 + 附加 pendingConfirm |
| 前端模式 | `chat.js` 已有三模式分支：channel+session（长连接）/ channel 单次流（旧接口）/ A2A | 收敛为两模式：单次流 / A2A |
| 断线重连 | `api.js` subscribeSession 有 backoff 重连（无游标） | 删除（单次流无需重连） |

---

## 3. 目标架构

```
① 对话（单次流，任意副本）
前端 ──POST /threads/{sid}/chat (SSE)──▶ Pod X
      ├─ 抢 Turn 租约（等待式：同 session 有活跃执行段 → 排队，SSE 发 waiting 帧）
      ├─ sendStream → 事件直吐（0 延迟，含 MCP ui 元数据）
      │   ├─ 工具类事件 → 异步批量写 tool_audit_log（失败静默）
      │   └─ permission_ask → ConfirmContext 落库 confirm_context + release（执行段结束，锁让出）
      └─ AGENT_END / error 帧 → 关闭流、释放租约

② HITL 暂停（人工决策期间）
- turn 执行段已结束（Pod 无活跃执行），状态已持久化（agent_state + confirm_context）
- 前端弹确认卡片；原 /chat 流被前端 abort（或已被 Nginx 断开）
- **期间新消息可直接 acquire 执行新 turn**（锁已让出，无需排队）
- 确认上下文（tool_calls + consumed=0）在 confirm_context 表，任意副本可读

③ 决策重开流（单次流，恢复 = 新的活跃执行段）
前端 ──POST /threads/{sid}/confirm-stream (SSE)──▶ Pod Y（可与 Pod X 不同）
      ├─ DB CAS（UPDATE consumed=0→1）防重复确认；miss→404 / consumed→409
      ├─ 反序列化 tool_calls → ConfirmResult → agent.streamEvents(resumeMsg, ctx)
      ├─ 恢复执行 → 事件直吐 + 工具审计落库
      └─ AGENT_END → 关闭、释放租约

④ 刷新重建（会话历史/状态，无事件回放）
前端刷新 → 恢复当前 sid（localStorage 持久化，需新增）
      ├─ GET /threads/{sid}/history → messages 回显（agent_state 块级）
      ├─ 响应附 pendingConfirm（confirm_context 存在且未 consumed 未过期）
      └─ 有 pendingConfirm → 弹确认卡片 → 走 ③
```

---

## 4. 详细设计

### 4.1 数据表设计

#### 4.1.1 confirm_context（HITL 确认上下文，跨副本共享）

```sql
CREATE TABLE IF NOT EXISTS confirm_context (
  session_id      VARCHAR(255) PRIMARY KEY,   -- fullThreadId（makeThreadId 补全 tenant 前缀）
  tool_calls_json MEDIUMTEXT NOT NULL,        -- [{id, name, input}]（ToolUseBlock 字段重建来源）
  reply_id        VARCHAR(64),
  created_at      DATETIME(3) NOT NULL,
  consumed        TINYINT(1) NOT NULL DEFAULT 0,
  KEY idx_created_at (created_at)
)
```

- **键**：`fullThreadId`（与 SDK RuntimeContext 一致；`makeThreadId` 的 tenant 前缀各副本一致，由配置派生）。
- **存储内容**：不序列化整个 `ToolUseBlock`（final 类 + Jackson 多态风险），只存 `{id, name, input}` 字段 JSON；恢复时用 `ToolUseBlock.builder()/.id().name().input()` 或公共构造器重建实例（javap 已确认 `builder()` 存在）。
- **CAS 消费**：`UPDATE confirm_context SET consumed=1 WHERE session_id=? AND consumed=0`；affected=0 且行存在 → `ConfirmAlreadyConsumedException`（409）；行不存在/过期 → `ConfirmContextNotFoundException`（404）。404/409 语义与现有接口完全一致。
- **TTL**：沿用 30min（`CONFIRM_TTL_MINUTES` 可配置）；懒判断（读时校验 `created_at`）+ 定时清理。
- **覆盖语义**：同 session 新 ASK 覆盖旧条目（与现缓存语义一致）。

#### 4.1.2 tool_audit_log（工具调用轻量审计）

```sql
CREATE TABLE IF NOT EXISTS tool_audit_log (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id  VARCHAR(255) NOT NULL,
  tool_name   VARCHAR(255) NOT NULL,
  tool_call_id VARCHAR(64),
  state       VARCHAR(32),                  -- TOOL_CALL_START / TOOL_CALL_END / TOOL_RESULT_START / TOOL_RESULT_END
  payload_json MEDIUMTEXT,                  -- SSE 词表一致（含 MCP ui 元数据；仅元信息，见 R2 定稿）
  created_at  DATETIME(3) NOT NULL,
  KEY idx_session (session_id, id),
  KEY idx_created_at (created_at)
)
```

- **写入范围**：仅工具类事件（`ToolCallStartEvent` / `ToolCallEndEvent` / `ToolResultStartEvent` / `ToolResultEndEvent`），不含文本 delta 与参数累积 delta。
- **审计粒度（O3 定稿）**：仅元信息——何时、何工具、何状态（`ToolCallStartEvent` 无 input 字段，参数不可得，见 R2）。不累积 `ToolCallDeltaEvent`。
- **写入方式**：异步批量（Reactor buffer 100ms 或 50 条合并），失败静默降级，不阻塞 SSE 直吐。
- **保留期**：默认 30 天（`EVENT_LOG_RETENTION_DAYS`），日级定时清理。
- **payload**：复用 `AgentEventSseSerializer.payload(event)` 词表（与前端一致，含 MCP ui 元数据）。

#### 4.1.3 turn_lease（Turn 租约，执行权互斥，O1 定稿）

> **定位**：租约锁并非无状态正确性必需品——`MysqlAgentStateStore` 自带 CAS（getVersioned/saveIfVersion）已兜底写冲突。租约解决的是**产品语义**：同一 session 的**活跃执行段**并发会产生重复 turn（重复调用 LLM、上下文分裂、回复互相覆盖），故以租约将执行段串行化（官方 SessionTurnGate 同为可选组件，语义一致）。
> **锁的边界**：锁覆盖**活跃执行段**（消息进入 → AGENT_END/error/permission_ask 暂停点），**不覆盖人工决策挂起期**——permission_ask 时 turn 已暂停、状态已持久化，执行段结束即让出锁，挂起期间新消息可自由进入。

```sql
CREATE TABLE IF NOT EXISTS turn_lease (
  session_id VARCHAR(255) PRIMARY KEY,
  token      CHAR(36) NOT NULL,             -- 本 turn 租约凭证（UUID）
  expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL
)
```

采用**租约 token + 短 TTL + 续租**模式（不用 `GET_LOCK`，避免长 turn 耗尽连接池，见 R4）：

**加锁 acquire(sessionId, waitTimeout) → token|null（等待式）**
```
① 立即尝试：
     INSERT INTO turn_lease VALUES (sid, uuid(), NOW(3)+60s, NOW(3))
       成功 → 拿锁，返回 token
     PK 冲突 → 读该行：
       未过期 → 进入 ② 排队等待
       已过期（前持有者已崩溃）→ 条件接管：
         DELETE FROM turn_lease WHERE session_id=? AND expires_at < NOW(3)
         → affected=1 才重试 ①；=0（被别的副本抢先）→ 进入 ②
② 排队等待：每 500ms 重试 ①（轮询为独立短连接，不占用连接池），
   等待期间由上层发 waiting 帧（见 4.2.1）
③ 超过 waitTimeout（默认 120s）仍未拿到 → 返回 null（HTTP 409 turn_in_progress，兜底）
```
两个副本同时接管时 `DELETE` 只影响 1 行，重试 `INSERT` 仅一个成功——原子性由 PK 唯一约束保证。

**续租 renew(sessionId, token)**（长 turn 防误释放；独立调度任务，每 20s 一次，TTL 60s）
```
UPDATE turn_lease SET expires_at = NOW(3)+60s WHERE session_id=? AND token=?
→ affected=0 说明租约已被接管/释放 → 停止续租（优雅退出信号）
```

**释放 release(sessionId, token)**（token 校验防误删他人锁）
```
DELETE FROM turn_lease WHERE session_id=? AND token=?
```

**生命周期与释放时机**（执行权语义）：
- 续租任务独立于 SSE Flux 生命周期（ScheduledExecutor），与 turn 执行器生命周期绑定；
- **release 触发点 = 活跃执行段结束**：
  - `AGENT_END`（正常完成）→ release + 停续租；
  - `error`（失败）→ release + 停续租；
  - **`permission_ask`（HITL 暂停点）→ 状态已持久化，立即 release + 停续租，锁让出**；
- **客户端断开（abort / Nginx 断流）不触发锁操作**——前端 abort 只是"观众离场"（见 R12 对 cancel 的处置）；
- **confirm-stream（恢复执行）是新执行段 → 需 acquire（等待式）**，AGENT_END 后 release；
- **副本崩溃**：续租停止 → TTL 60s 过期 → 其他副本可接管（无需心跳探测）。

**续租停止触发点全量清单**（续租任务绑定 **turn 执行器生命周期**，与 SSE 连接无关——SSE 流仅是 turn 事件的展示通道；租约是"执行权"凭证，其生命周期 = 活跃执行段）：

| # | 触发点 | 处置 | 状态 |
|---|--------|------|------|
| ① | AGENT_END（正常完成） | release + 停续租 | 定稿 |
| ② | error（turn 失败） | release + 停续租 | 定稿 |
| ③ | permission_ask（HITL 暂停点） | release + 停续租（执行段结束，锁让出） | 定稿 |
| ④ | turn 被取消（stop → abort → cancel） | 见 R12，依赖 SPIKE S2 结果 | 待 S2 定稿 |
| ⑤ | 租约被接管（续租 UPDATE affected=0） | 自停（仅剩崩溃兜底场景触发） | 定稿 |
| ⑥ | 进程崩溃 | TTL 60s 自然过期 | 定稿（兜底） |

> **为何不与 SSE 流绑定**：SSE 连接只是展示通道；续租锚在 turn 执行 Flux 的 `doOnComplete` / `doOnError` / `doOnCancel` 出口及 permission_ask 检测点（`doOnCancel` 的处置按 S2 语义，见 R12）。

### 4.2 接口设计

| 方法 | 路径 | 状态 | 说明 |
|------|------|------|------|
| POST | `/threads/{sid}/chat` | **改造** | SSE 单次流直吐；请求体 `{message, userId}` 不变；排队等待发 waiting 帧；等待超时（120s）→ 409=`turn_in_progress` |
| GET | `/threads/{sid}/events` | **删除** | 长连接订阅端点移除 |
| POST | `/threads/{sid}/confirm-stream` | **改造** | 语义不变（预检 404/409 → error SSE 帧；恢复事件流 → done 帧）；**新增 acquire（等待式）**：恢复执行是新执行段，与其他活跃执行段互斥，排队时发 waiting 帧 |
| POST | `/threads/{sid}/confirm` | 保留 | 同步版不动 |
| GET | `/threads/{sid}/history` | **扩展** | 响应附加 `pendingConfirm` 字段 |
| GET | `/chat/stream`（旧） | 保留 | 兼容不动 |

> **路径规范（O7 定稿）**：会话业务接口统一**去掉 `/debug` 前缀**，与现有 `ConfirmController`（`/threads/{sessionId}`）、`ThreadController`（`/threads`）对齐：
> - `SessionStreamController` 基路径 `/debug/threads/{sessionId}` → `/threads/{sessionId}`；
> - `DebugApiController` 下会话 API `GET /debug/threads`、`/debug/threads/{sessionId}/history`、`/debug/threads/{sessionId}/llm-calls` → `/threads`、`/threads/{sessionId}/history`、`/threads/{sessionId}/llm-calls`（`ThreadController` 已有 `GET /threads`，合并后从 `DebugApiController` 移除）；
> - 页面数据端点（`/debug/config/env`、`/debug/database/**`、`/debug/memory`、`/debug/workspace`、`/debug/logs`、`/debug/sandbox`）不在会话业务范围，保留 `/debug`。
> - 前端 `api.js` 中对应请求基路径同步调整。

#### 4.2.1 POST /chat 单次流（改造后）

```
token = turnLease.acquire(sid, waitTimeout=120s)     ← 等待式（内部 500ms 轮询，不占连接池）
等待期间：SSE 每 15s 发 {type:"waiting"} 帧           ← 防 Nginx 60s 读超时；前端提示"排队等待中"
超时仍未拿到 → 409 {error: "turn_in_progress"}       ← 兜底（长执行段 + 排队超时）
启动续租任务（20s 间隔，TTL 60s）                     ← 绑定执行器生命周期
sendStream(...)
  .map(event → toSSE(event, registrar))              ← 沿用现有词表 + MCP ui 元数据
  .doOnNext(event → 工具审计落库 / storeConfirmContext)
  .doOnNext(permission_ask → release(sid, token) + 停续租)   ← HITL 暂停点，执行段结束让出锁
  .doOnNext(AGENT_END → release(sid, token) + 停续租)
  .onErrorResume(e → errorSSE 帧 + release + 停续租)   ← 对齐 StreamController 语义 {type:error,error:...}
```

- **锁语义**：见 4.1.3。锁覆盖活跃执行段；permission_ask 暂停点即让出锁，挂起期间新消息可自由执行；confirm-stream 恢复是新执行段需重新 acquire。
- **waiting 帧**：前端收到后显示排队状态；stop 按钮 abort 连接即可取消等待（无服务端副作用——锁未被该请求持有）。
- **错误语义**：流中途失败发 `{type:error}` 帧后关闭；前端据此提示（对齐旧 /chat/stream）。

#### 4.2.2 history 响应扩展

```json
{
  "session_id": "...",
  "messages": [...],                      // 现有
  "pendingConfirm": {                     // 新增；无待确认时 null
    "replyId": "...",
    "tools": [{"tool_call_id":"...", "name":"...", "input":{...}}]
  }
}
```

- 查询 confirm_context 需做 session_id **前缀兼容**（同 history 现有 SQL：`session_id = ? OR LIKE '%:?'`），避免 raw sid / fullThreadId 格式差异导致刷新后查不到（Review R7）。

### 4.3 服务组件

| 组件 | 动作 | 说明 |
|------|------|------|
| 新增 `service/TurnLeaseStore.java` | 新建 | turn_lease 表 acquire/renew/release + TTL 兜底清理（4.1.3 算法） |
| 新增 `service/ConfirmContextStore.java` | 新建 | confirm_context 表 CRUD（含 lease_token）+ CAS + TTL 清理；建表模式参考 `UiContextStore.java:52` |
| 新增 `service/ToolAuditStore.java` | 新建 | tool_audit_log 异步批量写（仅工具类元信息）+ 定时清理 |
| `service/AgentRuntimeService.java` | 改造 | `confirmCache`/`ConfirmContext`/`putConfirmContext`/`consumeConfirmContext`/`sweepExpiredConfirms` 全部改走 ConfirmContextStore；`buildResumeMsg` 从 DB 取字段重建 ToolUseBlock；`resumeWithConfirmStream` 移除 eventBus.emit、挂审计落库、**acquire 租约（恢复 = 新执行段）+ AGENT_END/error 释放** |
| `controller/SessionStreamController.java` | 改造 | **基路径 `/debug/threads/{sessionId}` → `/threads/{sessionId}`**（O7）；POST /chat → 单次流 + TurnLease 等待式 acquire（waiting 帧）+ error 帧；GET /events 删除 |
| `controller/DebugApiController.java` | 扩展 | **会话 API 迁移到 `/threads`**（`/threads`、`/threads/{sessionId}/history`、`/threads/{sessionId}/llm-calls`，与 ThreadController 的 `GET /threads` 合并，O7）；threadHistory 附 pendingConfirm；页面数据端点留在 `/debug` |
| `service/SessionEventBus.java` | **删除** | 无长连接消费者；`AgentScopeConfig.java:335-347` Bean 装配与构造参数同步清理 |
| `service/SessionCleanupService.java` | 扩展 | 清理 Agent 数据时联动清理 confirm_context / tool_audit_log（O2 定稿） |
| `config/AgentManagerProperties.java` | 扩展 | `EVENT_LOG_RETENTION_DAYS`(30)、`CONFIRM_TTL_MINUTES`(30)、`TURN_LEASE_TTL_SECONDS`(60)、`TURN_LEASE_RENEW_SECONDS`(20) |

### 4.4 前端设计

| 文件 | 改动 |
|------|------|
| `js/api.js` | 请求基路径由 `/debug/threads` 改为 `/threads`（O7）；`triggerSessionChat` → POST-SSE 单次流读取（复用现有 fetch reader 逻辑，原 `subscribeSession` 改造而来）；删除 `subscribeSession`；`getThreadHistory` 解析 `pendingConfirm` |
| `modules/chat.js` | `sendMessage` 统一走 POST /chat 单次流（以 `sendChannelSingleStream` 为基底换接口）；**新增 `waiting` 帧处理**（显示"排队等待中"状态，可 stop 取消）；删除 `setupSessionWatch`/`closeSubscription`/`sendChannelTrigger`/长连接分支（1033-1085 行区域）；确认衔接沿用现有 abort→confirm-stream（729-735 行）；**刷新自动恢复**：恢复 sid → history 回显 → pendingConfirm 弹卡片 |
| `js/state.js` | `threads.current` 增加 localStorage 持久化（现状仅 theme 持久化，Review R6） |
| `js/state.js` + config 模块 | 移除 `streamMode` 长连接选项；**选择器保留 单次流 / A2A 两项**（O6 定稿） |

---

## 5. 兼容性与清理范围

- `ConfirmController` 404/409 错误码语义不变（`confirm_context_not_found` / `confirm_already_consumed`）；
- SSE 词表不变（`AgentEventSseSerializer` 零改动），前端 `handleEvent` 渲染逻辑零改动；
- `MySqlTaskStore`（A2A tasks/get）读取路径不动；
- 清理（O2 定稿）：`SessionCleanupService` 清理 Agent 数据时**联动清理** confirm_context / tool_audit_log / turn_lease 三张表，避免孤儿数据。

---

## 6. 验证方案

### 6.1 SPIKE（实施第 1 步，先验证后编码）

| # | 验证项 | 方法 | 判定 |
|---|--------|------|------|
| S1 | ToolUseBlock 字段重建 | 单测：`ToolUseBlock.builder().id().name().input()` 重建实例 → `ConfirmResult(confirmed, toolCall)` → resume | 构造器可重建即通过（javap 已确认 builder 存在，风险低） |
| S2 | **abort 后 turn 挂起状态**（核心风险 R3-a） | 双实例同库：Pod A POST /chat 至 permission_ask → 前端 abort 流 → 验证 turn 仍挂起 → Pod B confirm-stream 恢复 | 跨副本恢复成功即通过；失败 → 退路见 R3 |
| S2b | **挂起期间新 turn 后 confirm 恢复**（核心风险 R3-b，执行权语义前提） | 同上：挂起期间在任意 Pod 执行一次新 turn（走完 AGENT_END）→ 再 confirm 原挂起 turn → 验证恢复正确性（agent_state 单槽未被破坏） | 恢复正确即通过；失败 → 回退"挂起期间锁保持"（R3 退路 b） |
| S3 | Turn 租约（turn_lease 定稿方案） | 双 Pod 并发同 session 触发 → 第二个排队（waiting 帧）→ 第一个结束后自动执行；permission_ask 时租约让出、挂起期间新消息直接执行；confirm 恢复 acquire；模拟崩溃（停续租）60s 后可接管 | 排队/让出/接管语义正确 |

### 6.2 回归测试

- 现有 ~383 用例中 confirm 相关测试（进程内缓存语义）替换为 DB 版（ConfirmContextStore 单测）；
- 手动链路：单次流对话 / HITL 暂停→决策→恢复 / 刷新重建+弹卡片 / 审计表落库与清理；
- 双实例部署：跨副本 chat、跨副本 confirm、并发触发排队（waiting 帧 → 前 turn 结束自动执行）；
- **E2E 脚本回归（O5 定稿）**：`hitl-e2e-test.js`（改单次流）、`hitl-modes-test.js`（长连接分支删除/改测单次流）、`mcpapps-e2e.js`（切单次流）全部跑通。

---

## 7. 实施步骤

1. SPIKE S1/S2/S2b（先行验证，决定执行权语义是否成立与退路）
2. 后端：TurnLeaseStore → ConfirmContextStore → ToolAuditStore → AgentRuntimeService → SessionStreamController → DebugApiController → SessionCleanupService 联动 → 装配/配置 → 删 SessionEventBus
3. 单测更新 + `mvn test` 全量回归
4. 前端：api.js → chat.js → state.js（sid 持久化）/ 模式收敛（选择器保留 单次流/A2A）
5. E2E 脚本同步改造（O5）：hitl-e2e-test.js / hitl-modes-test.js / mcpapps-e2e.js → 单次流模式
6. 手动链路验证（6.2）+ 双实例验证 + E2E 回归
7. `AGENTS.md` / docs 同步更新

---

## 8. Design Review（问题与矛盾清单）

> 本节为设计自检结论，不改设计，逐条列问题、影响与建议，待拍板项见第 9 节。

### R1. ToolUseBlock 跨进程恢复 — 风险已消解
原设计担心 Jackson 反序列化整个 ToolUseBlock。**javap 核实**：`ToolUseBlock` 为 final 类但提供 `builder()` 与公共构造器 `(id, name, input)`。定稿为 confirm_context 存 `{id,name,input}` 字段 JSON、恢复时重建实例。**矛盾消除**，SPIKE S1 仅作确认。

### R2. 【矛盾-已定稿】审计目标 vs 事件词表：ToolCallStartEvent 无 input
**问题**：此前沟通声称审计可查"传了什么参数"，但 `ToolCallStartEvent` 仅有 `getToolCallId/getToolCallName`（javap 核实），参数在 `ToolCallDeltaEvent` 中流式累积。
**定稿（O3=a）**：审计降级为**仅元信息**（何时/何工具/何状态），不落参数、不累积 delta。审计价值定位为"调用事实记录"，与"轻量"定位一致。参数如需追溯：HITL 工具可从 confirm_context 查，已执行工具可从 agent_state（压缩后）查。

### R3. 【核心风险】abort/cancel 后 turn 挂起状态是否保持 + 挂起期间新 turn 的 SDK 行为
**问题**：a) 前端收到 permission_ask 后（决策时）abort 原 /chat 流（chat.js:730 现有逻辑）；SSE 写失败 → Reactor 订阅 cancel → 若 SDK 将 cancel 视为中断，**turn 会被取消而非挂起**，HITL 恢复无从谈起。b) 执行权语义下（R5 定稿），挂起期间新消息可执行新 turn——但 `AgentState` 按 `(userId, sessionId)` 单槽存储，新 turn 的 save 可能覆盖挂起 turn 的暂停状态，导致之后 confirm 恢复拿错上下文。
**影响**：a) 整个"暂停→持久化→重开流"架构的成败关键；b) 决定"挂起期间新消息可进入"（执行权语义）是否成立，不成立则回退为挂起期间排队。
**验证**：SPIKE S2 + S2b 必做：
- S2：abort 后 turn 仍挂起 → Pod B confirm-stream 可恢复；
- S2b：挂起期间执行一次新 turn（走完 AGENT_END）→ 再 confirm 原挂起 turn → 验证恢复正确性。
退路：a) 前端不 abort、仅断开连接（由 Nginx 超时断开）；b) 若 S2b 失败（新 turn 破坏恢复点）→ confirm 恢复期间锁定排他（confirm 排队期间新消息也排队，即回退"挂起期间锁保持"语义）。

### R4. 【矛盾-已定稿】GET_LOCK 长持有 vs 连接池默认 10 连接
**问题**：Turn 锁若用 `GET_LOCK` 须专用连接持有整个 turn（长任务可达数十分钟），`application.yml` 未配置连接池（Hikari 默认 max=10），并发长 turn 会耗尽连接池；且 GET_LOCK 为**连接级**锁。
**定稿（O1=b）**：采用 `turn_lease` 表锁（4.1.3）——租约 token + 短 TTL 60s + 20s 续租。不占连接、租约状态可查可运维（`SELECT * FROM turn_lease`）、副本崩溃 TTL 过期自动接管、无需心跳探测。
**定位澄清**：租约非无状态正确性必需——`MysqlAgentStateStore` CAS 已兜底写冲突；租约仅提供"同 session 串行化"的产品语义（防重复 turn），等待式 acquire 将并发触发排队而非拒绝。

### R5. 【语义冲突-已定稿】租约与 confirm-stream 的关系（执行权语义）
**问题**：租约若持有到 HITL 挂起期间，人工决策可能数十分钟，锁被"空占"阻塞新消息；但 confirm-stream 恢复执行是活跃执行，与其他执行段并发有写冲突风险。
**定稿规则**（执行权语义，详见 4.1.3/4.2.1）：
- 锁覆盖**活跃执行段**，permission_ask 暂停点 = 执行段结束 → **立即 release 让出锁**；
- 挂起期间新消息**可直接 acquire 执行**（状态已持久化，无需排队）；
- **confirm-stream 恢复 = 新执行段 → 需 acquire（等待式）**，与其他活跃执行段互斥；防重复确认靠 confirm_context CAS；
- 客户端断开（abort / Nginx 断流）不触发锁操作。

### R6. 【缺口】刷新恢复的 sid 持久化不存在
**问题**：`state.js` 目前仅持久化 theme（localStorage），`threads.current` 未持久化——刷新后前端连当前会话都找不到，"刷新自动恢复"无从开始。
**影响**：功能缺口，需新增 sid 的 localStorage 持久化（写入 state.js，随会话切换更新）。

### R7. 【一致性】pendingConfirm 查询的 session_id 前缀问题
**问题**：history 现有 SQL 用 `session_id = ? OR LIKE '%:?'` 兼容前缀；confirm_context 键为 fullThreadId（带 tenant 前缀）。若 pendingConfirm 查询只按 raw sid 精确匹配，会因格式差异查不到。
**影响**：刷新后确认卡片不弹，HITL 恢复入口丢失。
**定稿规则**：pendingConfirm 查询复用 history 相同的前缀兼容 SQL。

### R8. 【边界-已定稿】HITL 挂起期间 Nginx 超时断流
**问题**：permission_ask 后服务端无数据输出，Nginx `proxy_read_timeout`（默认 60s）会断开 SSE 连接。
**定稿**：断流无副作用——permission_ask 时锁已让出（R5 定稿），turn 挂起状态由 agent_state 承载；前端在收到 permission_ask 后**主动 abort** 为最优路径（省资源、语义清晰）。断流不影响后续 confirm-stream 恢复。

### R9. 【范围确认】双写路径的一致性
`POST /chat`（Channel 链路）与 `confirm-stream`（AgentRuntimeService 链路）各自 `doOnNext` 挂审计落库，需保证两处行为一致（同一 ToolAuditStore 方法）。设计已定：统一在事件处理函数中调用，避免两套序列化。

### R10. 【孤儿数据-已定稿】清理归属
confirm_context（consumed/过期条目）、tool_audit_log（过期条目）、turn_lease（残留租约）均需清理。**定稿（O2=a）**：`SessionCleanupService` 清理 Agent 数据时联动清理三表；同时保留各表自身的 TTL 定时清理（confirm_context 30min、tool_audit_log 30 天、turn_lease 过期行）作为兜底。

### R11. 【多标签页/多端-已定稿】同 session 并发语义（执行段排队）
单次流架构下同 session 两个标签页同时发消息：第二个请求**排队等待**（waiting 帧提示），前一活跃执行段结束后自动执行；等待超 120s 才返回 409（兜底，提示"该会话有进行中的任务"）。**HITL 挂起期间新消息不排队**（锁已让出，见 R5）。另：页面 A 在 HITL 挂起、页面 B 刷新后弹确认卡片并决策——页面 A 的卡片因无事件回流保持原状，属可接受（多端最终一致由 history 保证）。

### R12. 【缺口-待 S2 定稿】stop/取消场景（permission_ask 前）的续租停止条件
**问题**：续租任务与 turn 执行器生命周期绑定；permission_ask 前的 stop（用户点 stop → abort → Flux cancel）时 turn 的处置未定义——若 cancel 中断 turn 而续租不停，锁会残留最长 60s（TTL 兜底）影响下一次请求；若 cancel 仅断订阅而 turn 后台继续跑，stop 按钮语义退化为"只断流不停止"。
**定稿规则**（取决于 SPIKE S2 结果，注意与 R3 的区别：本项仅覆盖 **permission_ask 之前** 的 cancel，HITL 挂起期的断流见 R8）：
- S2 证实 **cancel = 中断 turn** → turn 执行器的 cancel 回调中 release + 停续租（锁立即让出）；
- S2 证实 **cancel = 仅断订阅、turn 后台继续** → 续租继续、锁保持至 AGENT_END（语义自洽）；stop 的产品语义需另行确认（维持现状"只断流"或新增服务端 cancel 能力，后者需查 SDK 支持，可能超出本次范围）；
- 无论哪种结果，**双层兜底保证不会永久锁死**：TTL 60s 租约过期 + 排队超时 120s。

---

## 9. 决策记录（已定稿）

| # | 问题 | 定稿 | 依据 |
|---|------|------|------|
| O1 | Turn 租约实现 | **turn_lease 表锁，执行权语义**（token + TTL 60s + 20s 续租；仅覆盖活跃执行段，permission_ask 暂停点即让出；并发执行排队 + waiting 帧，超时 120s 才 409，4.1.3） | R4/R5/R11：不占连接池、崩溃自恢复；挂起期间新消息可自由进入；正确性本身由 agent_state CAS 兜底，租约仅为执行段互斥 |
| O2 | 两表清理归属 | **挂 SessionCleanupService 联动** + 各表 TTL 兜底 | R10 |
| O3 | 审计粒度 | **仅元信息**（何时/何工具/何状态），不落参数 | R2：ToolCallStartEvent 无 input |
| O4 | 旧 GET /chat/stream 与前端旧单次流分支 | 保留（非目标） | — |
| O5 | E2E 脚本同步改造 | **纳入本次范围**：hitl-e2e-test.js / hitl-modes-test.js / mcpapps-e2e.js 改走单次流模式 | 长连接删除后 E2E 设施必须同步，否则破坏 |
| O6 | 前端模式选择器 | **保留，仅剩 单次流 / A2A 两项**（长连接选项移除） | 便于调试对比两种协议 |
| O7 | API 路径规范 | **会话业务接口去掉 `/debug` 前缀**：chat/events/history/llm-calls/threads 迁移到 `/threads`（与 ConfirmController/ThreadController 对齐）；页面数据端点保留 `/debug` | 统一会话 API 命名空间，避免"调试专属"误读；4.2 接口表说明 |

> 所有开放问题已关闭，本设计进入定稿状态；剩余风险仅 R3、R12 两项，均依赖 SPIKE S2（cancel 语义）验证收敛。

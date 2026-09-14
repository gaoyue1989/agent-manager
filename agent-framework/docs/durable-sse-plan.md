# SSE 长任务解耦改造设计（durable-sse-plan）

> 状态：**设计稿**，待评审
> 范围：agent-framework（AgentScope Java 2.0.0 + Spring Boot 3.3）
> 前置：已完成 stateless-single-stream-plan，当前架构为无状态单次流 SSE 直吐
> 目标：解耦 SSE 连接与 agent 执行生命周期，支持长任务不中断、刷新恢复续传

---

## 1. 背景与问题

### 1.1 现状

当前架构（`stateless-single-stream-plan` 定稿）：

```
前端 ──POST /threads/{sid}/chat (SSE)──▶ Pod
      ├─ acquire turn 租约
      ├─ chatChannel.sendStream → 事件直吐到 FluxSink
      └─ sink.onCancel → agentSubscription.dispose() + lease.release()
```

核心特征：**SSE 连接 = agent 执行的输出管道，且是唯一管道**。断连即断管。

### 1.2 三个问题

| # | 问题 | 现状 | 影响 |
|---|------|------|------|
| P1 | **长任务断连**：agent 执行十几/几十分钟，Nginx `proxy_read_timeout` 默认 60s，执行期无事件窗口超时即断 | 排队期有 waiting 帧（15s），执行期无心跳 | 🔴 长沙箱执行、长 LLM 推理时 Nginx 砍连接 |
| P2 | **刷新杀任务**：`onCancel` 回调中 `agentSubscription.dispose()` | 用户刷新/关闭标签页 → agent 管道被 dispose → 正在执行的 LLM 调用、工具执行全部中断 | 🔴 几十分钟的任务因一次刷新归零 |
| P3 | **无法续传**：事件仅写入当前 FluxSink，无持久化无广播 | 断连后无法重新订阅，已发出的事件前端也收不到 | 🔴 刷新后无法恢复执行进度 |

### 1.3 目标

- **agent 执行不受前端连接生命周期影响**：刷新、断网、Nginx 超时都不中断正在执行的任务
- **前端可重连续传**：刷新后可订阅正在执行的任务，收到后续事件 + 回放断连期间的增量事件
- **全程心跳**：agent 执行期间持续发心跳帧，防止 Nginx/CDN 超时
- **向后兼容**：SSE 词表不变（`AgentEventSseSerializer` 零改动），前端渲染逻辑零改动

### 1.4 非目标

- 不做 A2A 协议路径的改造（A2A 自带 `tasks/resubscribe`，不在本次范围）
- 不做事件全量审计（含文本 delta）——延续 `tool_audit_log` 仅元信息的定位
- 不做跨实例事件实时广播（单实例 `Sinks.Many` 即可；多实例需 Redis Pub/Sub，留作 P2 扩展点）
- 不改 `GET /chat/stream`（旧 StreamController，保留兼容）

---

## 2. 目标架构

### 2.1 核心思路

```
现在：前端 SSE ← FluxSink ← agent 事件（1:1 绑定，断即死）
改后：前端 SSE ← SessionEventBus ← agent 事件（解耦，断可续）
                          ↘ 事件持久化（回放用）
```

引入 **SessionEventBus** 作为 agent 执行与 SSE 连接之间的解耦层：
- agent 事件写入 EventBus，不直接写入 FluxSink
- SSE 连接从 EventBus 订阅，断连不影响 agent
- 事件同时持久化到 DB，用于断连回放

### 2.2 交互流程

```
① 发起对话
前端 ──POST /threads/{sid}/chat (SSE)──▶ Pod
      ├─ acquire turn 租约
      ├─ 启动 agent 执行（事件 → SessionEventBus）
      ├─ SSE 连接从 EventBus 订阅（含心跳）
      └─ sink.onCancel → 仅取消 SSE 订阅，不 dispose agent

② agent 执行中（与前端连接无关）
agent → EventBus.emit(event) → 持久化到 session_event 表
                              → 广播给所有 SSE 订阅者（含心跳注入）

③ 前端断连/刷新
SSE 连接断开 → EventBus 订阅取消 → agent 继续执行 → 事件持续持久化
前端重新加载 → 查询任务状态 → 如果 working 则打开新 SSE 订阅

④ 重连续传
前端 ──GET /threads/{sid}/subscribe (SSE)──▶ Pod
      ├─ 从 DB 回放 lastEventId 之后的事件
      ├─ 从 EventBus 订阅后续实时事件
      └─ 心跳保活

⑤ 任务完成
agent AGENT_END → EventBus 关闭该 session 的 Sinks → 所有 SSE 订阅者收到 done → 流关闭
```

---

## 3. 详细设计

### 3.1 数据表：session_event（事件持久化，回放用）

```sql
CREATE TABLE IF NOT EXISTS session_event (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,  -- 单调递增，用于游标回放
  session_id  VARCHAR(255) NOT NULL,
  seq         INT NOT NULL,                       -- 单 turn 内递增序号
  event_type  VARCHAR(64) NOT NULL,               -- AGENT_END / TEXT_BLOCK_DELTA / ...
  payload     MEDIUMTEXT NOT NULL,                -- AgentEventSseSerializer.payload() 输出
  reply_id    VARCHAR(64),                        -- 区分多 run（HITL 恢复等）
  created_at  DATETIME(3) NOT NULL,
  KEY idx_session_seq (session_id, seq),
  KEY idx_session_reply (session_id, reply_id, seq),
  KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
```

**设计决策**：

| 项 | 决策 | 理由 |
|---|------|------|
| 存储内容 | 复用 `AgentEventSseSerializer.payload()` 的 JSON | 词表一致，回放零转换 |
| seq vs id | `seq` 是 turn 内业务序号，`id` 是全局自增主键 | 回放用 `(session_id, seq)` 游标，`id` 用于物理分页清理 |
| payload 大小 | TEXT_BLOCK_DELTA 约百字节/帧，单 turn 数千帧 → 单 turn 约 100KB~1MB | MEDIUMTEXT(16MB) 足够 |
| reply_id | 区分同一 session 不同 turn（HITL 恢复产生新 turn） | 重连续传时可指定只回放特定 turn |
| 保留期 | 默认 7 天（与 agent_state 对齐），SessionCleanupService 联动清理 | 避免无限膨胀 |

### 3.2 核心组件：SessionEventBus

```java
@Service
public class SessionEventBus {

    /** session_id → Sinks.Many<EnvelopedEvent> */
    private final ConcurrentHashMap<String, Sinks.Many<EnvelopedEvent>> sinks = new ConcurrentHashMap<>();

    private final SessionEventStore eventStore;

    /** 帧间隔上限：无业务事件时每 20s 发一次心跳 */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(20);

    // ===== 事件发布（agent 执行侧调用） =====

    /**
     * 发射一个 agent 事件。
     * 1. 持久化到 session_event 表
     * 2. 包装为 EnvelopedEvent 广播给所有 SSE 订阅者
     */
    public void emit(String sessionId, AgentEvent event, String replyId) {
        String payload = AgentEventSseSerializer.payload(event);
        int seq = eventStore.append(sessionId, replyId, event.getType().name(), payload);

        EnvelopedEvent enveloped = new EnvelopedEvent(seq, event.getType().name(), payload, replyId);

        var sink = sinks.get(sessionId);
        if (sink != null) {
            sink.tryEmitNext(enveloped);  // 无消费者时不报错
        }
    }

    /**
     * 发射合成事件（如 file_ready、waiting、error）。
     * 同上流程，但不来自 AgentEvent。
     */
    public void emitSynthetic(String sessionId, String replyId, String type, String payload) {
        int seq = eventStore.append(sessionId, replyId, type, payload);
        EnvelopedEvent enveloped = new EnvelopedEvent(seq, type, payload, replyId);

        var sink = sinks.get(sessionId);
        if (sink != null) {
            sink.tryEmitNext(enveloped);
        }
    }

    // ===== SSE 订阅（控制器侧调用） =====

    /**
     * 创建新 SSE 订阅。
     * @param afterSeq 回放起点（0 = 不回放，从当前开始）
     * @return Flux<ServerSentEvent<String>>
     */
    public Flux<ServerSentEvent<String>> subscribe(String sessionId, int afterSeq, String replyId) {
        var sink = sinks.computeIfAbsent(sessionId, k ->
            Sinks.many().multicast().onBackpressureBuffer(256));

        // 1. 回放历史事件
        Flux<ServerSentEvent<String>> replay = Flux.defer(() -> {
            if (afterSeq <= 0) return Flux.empty();
            return eventStore.queryAfter(sessionId, replyId, afterSeq)
                .map(this::toSSE);
        });

        // 2. 实时事件流（含心跳注入）
        Flux<ServerSentEvent<String>> live = sink.asFlux()
            .filter(e -> replyId == null || replyId.equals(e.replyId()))
            .map(this::toSSE);

        // 3. 心跳流：无业务事件时每 20s 发一次
        Flux<ServerSentEvent<String>> heartbeat = Flux.interval(HEARTBEAT_INTERVAL)
            .map(i -> ServerSentEvent.<String>builder()
                .comment("hb")   // SSE comment 帧，不触发前端 EventSource.onmessage
                .build());

        return replay.concatWith(live.mergeWith(heartbeat))
            .doFinally(signal -> {
                // 最后一个订阅者离开后延迟清理 Sinks
                // （agent 仍在执行时保留，agent 结束时 closeSession 清理）
            });
    }

    /** turn 结束时关闭 session 的 Sinks（所有订阅者收到 onComplete） */
    public void closeSession(String sessionId) {
        var sink = sinks.remove(sessionId);
        if (sink != null) {
            sink.tryEmitComplete();
        }
    }

    /** 查询 session 当前的 turn 状态 */
    public TurnStatus turnStatus(String sessionId) {
        // 从 turn_lease 或 session_event 最后一条判断
        // working / completed / not_found
    }

    private ServerSentEvent<String> toSSE(EnvelopedEvent e) {
        return ServerSentEvent.<String>builder()
            .data(e.payload())
            .id(String.valueOf(e.seq()))   // Last-Event-ID 支持
            .build();
    }

    /** 带序号的包装事件 */
    record EnvelopedEvent(int seq, String type, String payload, String replyId) {}
}
```

**关键设计决策**：

| 项 | 决策 | 理由 |
|---|------|------|
| Sinks 类型 | `Sinks.many().multicast().onBackpressureBuffer(256)` | 多订阅者（多标签页），缓冲防丢失 |
| 心跳方式 | SSE comment 帧 `: hb` | 不触发 `EventSource.onmessage`，不干扰业务事件解析；但 Nginx 仍视为流量重置超时计时器 |
| 回放机制 | `afterSeq` 游标 | 前端记住 `lastEventId`（SSE 标准 `id` 字段），重连时从该游标后回放 |
| 单实例 vs 多实例 | 先做单实例（ConcurrentHashMap） | 当前部署规模够用；接口预留 Redis Pub/Sub 扩展点 |
| Sinks 生命周期 | agent turn 结束时 `closeSession` | 订阅者收到 `onComplete` → SSE 流关闭 → 前端显示完成 |

### 3.3 数据访问：SessionEventStore

```java
@Service
public class SessionEventStore {

    private final DataSource dataSource;

    /** 追加事件，返回分配的 seq */
    public int append(String sessionId, String replyId, String type, String payload) {
        // INSERT + 子查询获取当前 MAX(seq)+1
        // 或使用 session 内自增（需防并发，但同 session 由 turn_lease 串行化）
    }

    /** 查询 afterSeq 之后的事件（回放用） */
    public Flux<EnvelopedEvent> queryAfter(String sessionId, String replyId, int afterSeq) {
        // SELECT * FROM session_event
        // WHERE session_id = ? AND (reply_id = ? OR ? IS NULL) AND seq > ?
        // ORDER BY seq ASC
    }

    /** 清理过期事件（SessionCleanupService 调用） */
    public int deleteBefore(Instant cutoff) {
        // DELETE FROM session_event WHERE created_at < ?
    }
}
```

**并发安全**：同 session 的 turn 由 `turn_lease` 串行化，`seq` 分配无需额外锁。HITL 恢复（confirm-stream）是新 turn，`reply_id` 不同，不会冲突。

### 3.4 控制器改造

#### 3.4.1 POST /threads/{sessionId}/chat（改造）

```java
@PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> chat(@PathVariable String sessionId,
                                          @RequestBody ChatRequest body) {
    sessionId = PathSafe.sanitize(sessionId);
    String finalSessionId = sessionId;
    String userId = body.userId() != null ? PathSafe.sanitize(body.userId()) : "debug-user";

    return Flux.<ServerSentEvent<String>>create(sink -> {
        // ===== 1. 抢 Turn 租约（与现有一致） =====
        var token = acquireWithWaiting(sink, finalSessionId);
        if (token == null) { sink.complete(); return; }

        TurnLeaseGuard lease = new TurnLeaseGuard(turnLeaseStore, finalSessionId, token);

        // ===== 2. 准备 EventBus Sinks（确保 agent 事件有输出通道） =====
        String replyId = UUID.randomUUID().toString();
        sessionEventBus.ensureSink(finalSessionId);  // computeIfAbsent

        // ===== 3. 启动 agent 执行 → 事件写入 EventBus =====
        var messages = buildMessages(body, userId, finalSessionId);
        chatChannel.sendStream(ChatUiRequest.withPeer(finalSessionId, messages))
            .subscribe(
                event -> handleEventAndEmit(event, finalSessionId, replyId, lease),
                e -> { handleErrorAndEmit(e, finalSessionId, replyId, lease); },
                () -> handleComplete(finalSessionId, replyId, lease)
            );

        // ===== 4. SSE 订阅 EventBus（从 seq=0 开始，因为本 turn 的事件从 0 起） =====
        sessionEventBus.subscribe(finalSessionId, 0, replyId)
            .subscribe(
                sse -> sink.next(sse),
                e -> sink.error(e),
                () -> sink.complete()
            );

        // ===== 5. onCancel：仅取消 SSE 订阅，不 dispose agent =====
        sink.onCancel(() -> {
            log.info("SSE disconnected, agent execution continues (sid={}, rid={})",
                     finalSessionId, replyId);
            // 不调用 agentSubscription.dispose()
            // 不调用 lease.release() —— turn 仍在执行
        });

    }).subscribeOn(Schedulers.boundedElastic());
}
```

**核心变化对比**：

| 项 | 改造前 | 改造后 |
|---|--------|--------|
| 事件流向 | agent → FluxSink → SSE | agent → EventBus → 持久化 + SSE |
| onCancel | `agentSubscription.dispose()` + `lease.release()` | 仅取消 SSE 订阅 |
| 心跳 | 仅排队期 waiting 帧 | 全程心跳（EventBus 心跳流） |
| replyId | 无 | 有（区分 turn） |

#### 3.4.2 GET /threads/{sessionId}/subscribe（新增）

```java
@GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> subscribe(
        @PathVariable String sessionId,
        @RequestParam(required = false) Integer afterSeq,
        @RequestParam(required = false) String replyId) {

    sessionId = PathSafe.sanitize(sessionId);

    // 如果 turn 已完成，直接返回历史回放 + done 帧
    var status = sessionEventBus.turnStatus(sessionId);
    if (status == TurnStatus.COMPLETED) {
        return replayAndClose(sessionId, replyId, afterSeq);
    }

    // turn 进行中：回放 + 实时订阅
    return sessionEventBus.subscribe(sessionId,
        afterSeq != null ? afterSeq : 0,
        replyId);
}

private Flux<ServerSentEvent<String>> replayAndClose(String sid, String rid, Integer afterSeq) {
    return sessionEventBus.subscribe(sid, afterSeq != null ? afterSeq : 0, rid)
        .concatWith(Flux.just(ServerSentEvent.<String>builder()
            .data("{\"type\":\"done\"}")
            .build()));
}
```

**前端使用方式**：

```javascript
// 刷新后重连续传
const lastEventId = localStorage.getItem(`lastEventId_${sessionId}`) || 0;
const es = new EventSource(
  `/threads/${sessionId}/subscribe?afterSeq=${lastEventId}`
);
es.onmessage = (e) => {
  handleEvent(e);                      // 复用现有渲染逻辑
  localStorage.setItem(`lastEventId_${sessionId}`, e.lastEventId);
};
```

#### 3.4.3 GET /threads/{sessionId}/status（新增）

```java
@GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
public Map<String, Object> status(@PathVariable String sessionId) {
    sessionId = PathSafe.sanitize(sessionId);
    var leaseHeld = turnLeaseStore.isHeld(sessionId);
    var latestEvent = sessionEventStore.findLatest(sessionId);
    var pendingConfirm = confirmContextStore.findPending(sessionId);

    String state;
    if (pendingConfirm.isPresent()) {
        state = "waiting_confirm";
    } else if (leaseHeld) {
        state = "working";
    } else if (latestEvent.map(e -> "AGENT_END".equals(e.type())).orElse(false)) {
        state = "completed";
    } else {
        state = "idle";
    }

    return Map.of(
        "session_id", sessionId,
        "state", state,
        "latest_event_seq", latestEvent.map(EnvelopedEvent::seq).orElse(-1),
        "pending_confirm", pendingConfirm.orElse(null)
    );
}
```

**前端刷新后的恢复逻辑**：

```javascript
// 页面加载时
async function restoreSession(sessionId) {
  const resp = await fetch(`/threads/${sessionId}/status`);
  const { state, latest_event_seq, pending_confirm } = await resp.json();

  // 先加载历史消息（现有逻辑）
  await loadHistory(sessionId);

  if (state === 'working') {
    // 任务进行中，打开 SSE 续传
    openSubscribe(sessionId, latest_event_seq);
  } else if (state === 'waiting_confirm') {
    // HITL 待确认，弹确认卡片（现有逻辑）
    showConfirmCard(pending_confirm);
  }
  // completed / idle: 仅显示历史
}
```

### 3.5 事件处理函数改造

`SessionStreamController.handleEvent()` 当前直接 `sink.next(toSSE(event))`，需改为写入 EventBus：

```java
private void handleEventAndEmit(AgentEvent event, String sessionId,
                                String replyId, TurnLeaseGuard lease) {
    // 1. 工具审计（与现有一致，异步批量落库）
    audit(event, sessionId);

    // 2. present_file 累积逻辑（与现有一致）
    accumulatePresentFile(event);

    // 3. HITL 权限确认（与现有一致，释放租约让出执行权）
    if (event instanceof RequireUserConfirmEvent) {
        runtimeService.storeConfirmContext(sessionId, event);
        lease.release();
    }

    // 4. ★ 核心变化：事件写入 EventBus（而非直接写入 FluxSink）
    sessionEventBus.emit(sessionId, event, replyId);

    // 5. present_file 合成帧（通过 EventBus 发射合成事件）
    if (event instanceof ToolResultEndEvent tre && "present_file".equals(tre.getToolCallName())) {
        String payload = synthesizeFileReadyPayload(tre.getToolCallId());
        sessionEventBus.emitSynthetic(sessionId, replyId, "file_ready", payload);
    }

    // 6. turn 结束时关闭 EventBus
    if (event.getType() == AgentEventType.AGENT_END) {
        lease.release();
        sessionEventBus.closeSession(sessionId);  // 所有 SSE 订阅者收到 onComplete
    }
}
```

### 3.6 TurnLeaseStore 扩展

```java
// 新增方法
/** 检查 session 当前是否有活跃租约（status 端点使用） */
public boolean isHeld(String sessionId) {
    try (var conn = dataSource.getConnection();
         var stmt = conn.prepareStatement(
             "SELECT 1 FROM turn_lease WHERE session_id = ? AND expires_at > NOW(3)")) {
        stmt.setString(1, sessionId);
        var rs = stmt.executeQuery();
        return rs.next();
    } catch (Exception e) {
        return false;
    }
}
```

### 3.7 SessionCleanupService 扩展

```java
// cleanup() 方法中新增
// 5. 清理过期事件记录
int eventCleaned = sessionEventStore.deleteBefore(cutoff);
log.info("Session cleanup done: memory={}, agent_state={}, agent_fs={}, session_event={}",
    memCleaned, stateCleaned, fsCleaned, eventCleaned);
```

### 3.8 配置项扩展

```yaml
agent:
  # ... 现有配置 ...
  sse:
    # 心跳间隔（秒），防 Nginx/CDN 超时
    heartbeat-interval-seconds: ${SSE_HEARTBEAT_INTERVAL:20}
    # 事件保留天数
    event-retention-days: ${SSE_EVENT_RETENTION_DAYS:7}
    # EventBus 缓冲区大小（每个 session）
    eventbus-buffer-size: ${SSE_EVENTBUS_BUFFER_SIZE:256}
    # Sinks 过期清理延迟（秒）：最后一个订阅者离开后多久清理 Sinks
    sinks-eviction-delay-seconds: ${SSE_SINKS_EVICTION_DELAY:300}
```

---

## 4. 前端改造

### 4.1 api.js

```javascript
// ===== 新增：subscribe 端点 =====
function subscribeSession(sessionId, afterSeq = 0, replyId = null) {
  const params = new URLSearchParams({ afterSeq: String(afterSeq) });
  if (replyId) params.set('replyId', replyId);

  const es = new EventSource(`/threads/${sessionId}/subscribe?${params}`);

  es.addEventListener('message', (e) => {
    handleEvent(JSON.parse(e.data));
    // 记录 lastEventId 供刷新续传
    if (e.lastEventId) {
      localStorage.setItem(`sse_lastSeq_${sessionId}`, e.lastEventId);
    }
  });

  return es;
}

// ===== 新增：status 端点 =====
async function getSessionStatus(sessionId) {
  const resp = await fetch(`/threads/${sessionId}/status`);
  return resp.json();
}

// ===== 改造：chat 发起后改为 EventBus 订阅模式 =====
async function triggerChat(sessionId, message, fileIds = []) {
  // POST /chat 不再直接读取 SSE 响应流
  // 而是触发执行后，打开 subscribe 端点接收事件
  const resp = await fetch(`/threads/${sessionId}/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message, fileIds })
  });

  // 从响应头获取 replyId（服务端在第一个 SSE 帧中返回）
  const reader = resp.body.getReader();
  // ... 读取首个事件获取 replyId ...
  // 然后切换到 subscribe 端点接收后续事件
  return subscribeSession(sessionId, 0, replyId);
}
```

> **替代方案（更简洁）**：`POST /chat` 仍然返回 SSE 流，但前端同时记住 `lastEventId`。刷新后改用 `GET /subscribe` 续传。这样改动最小——前端 `chat.js` 的 `sendMessage` 核心逻辑不变，仅新增恢复逻辑。

**推荐替代方案**，原因：
- `POST /chat` SSE 直吐的体验最优（首事件延迟最低）
- 刷新是低频操作，`GET /subscribe` 只在恢复场景使用
- 前端改动最小

### 4.2 chat.js 改造

```javascript
// ===== sendMessage：保持 POST /chat SSE 直吐 =====
// 唯一变化：记录 lastEventId
async function sendMessage(text, fileIds = []) {
  // ... 现有逻辑 ...
  // POST /chat → EventSource/onmessage 处理
  // 新增：每收到一个事件，更新 lastEventId
  eventSource.onmessage = (e) => {
    handleEvent(JSON.parse(e.data));
    if (e.lastEventId) {
      localStorage.setItem(`sse_lastSeq_${currentSessionId}`, e.lastEventId);
    }
  };
}

// ===== 新增：页面加载时的恢复逻辑 =====
async function restoreSession() {
  const sessionId = localStorage.getItem('currentSessionId');
  if (!sessionId) return;

  await loadHistory(sessionId);  // 现有逻辑

  const status = await getSessionStatus(sessionId);
  if (status.state === 'working') {
    // 任务进行中 → 续传
    const afterSeq = parseInt(localStorage.getItem(`sse_lastSeq_${sessionId}`) || '0');
    subscribeSession(sessionId, afterSeq, status.reply_id);
  } else if (status.state === 'waiting_confirm') {
    // HITL 待确认 → 弹卡片
    showConfirmCard(status.pending_confirm);  // 现有逻辑
  }
}

// ===== HITL 确认后：复用 subscribe 续传 =====
async function confirmAndResume(sessionId, results) {
  // POST /confirm-stream 仍用 SSE 直吐
  // 或改为 POST /confirm（同步）+ GET /subscribe 续传
  // 推荐后者：confirm-stream 逻辑与 /chat 同构，无需重复
}
```

### 4.3 state.js 改造

```javascript
// 新增：currentSessionId 持久化
const state = {
  // ... 现有 ...
  threads: { current: null }
};

// 会话切换时自动持久化
function setCurrentSession(sessionId) {
  state.threads.current = sessionId;
  localStorage.setItem('currentSessionId', sessionId);
}

// 页面加载时恢复
function loadPersistedSession() {
  return localStorage.getItem('currentSessionId');
}
```

---

## 5. 关键场景分析

### 5.1 长时间工具执行（如沙箱跑代码 5 分钟）

```
时间线：
T0    前端 POST /chat → agent 开始执行
T1    agent 调用 execd 工具，沙箱开始跑代码
T2-T5 沙箱执行中，无业务事件
      ← EventBus 心跳流每 20s 发 :comment 帧重置 Nginx 计时器
T5    沙箱返回结果 → agent 继续推理
T6    AGENT_END → EventBus.closeSession → SSE 流关闭
```

**结果**：全程不断连，刷新也不影响。

### 5.2 用户刷新页面

```
T0    agent 执行中，SSE 推送事件
T3    用户刷新 → EventSource.close()
      → 服务端 onCancel 触发 → 仅取消 SSE 订阅
      → agent 继续执行 → 事件持续写入 EventBus + 持久化
T3'   页面加载 → restoreSession() → GET /status → working
      → GET /subscribe?afterSeq=lastSeq → 回放 T3 期间事件 + 实时续传
T10   AGENT_END → SSE 流关闭 → 前端显示完成
```

**结果**：刷新零感知，中间事件不丢失。

### 5.3 Nginx 超时断连

```
T0    SSE 连接正常
T60   Nginx proxy_read_timeout 断开（极端情况：心跳也未生效）
      → 服务端 onCancel → 仅取消 SSE 订阅
      → agent 继续执行 → 事件持续持久化
T61   前端 EventSource.onerror → 检测到断连
      → 自动重连：GET /subscribe?afterSeq=lastSeq
      → 回放 T60 期间事件 + 实时续传
```

**结果**：Nginx 断连自动恢复，EventSource 内置重连 + `Last-Event-ID` 机制天然支持。

### 5.4 HITL 暂停

```
T0    agent 执行 → 触发 permission_ask
T1    EventBus 发射 permission_ask 事件 → SSE 推给前端
      → confirmContext 落库 → turn lease 释放
      → EventBus.closeSession() ← 因为 HITL 是 turn 边界
T2    前端弹确认卡片 → 用户操作...
T3    用户确认 → POST /confirm-stream
      → acquire turn lease（新执行段）
      → 新 replyId → 新 EventBus sink
      → agent 恢复执行
```

**注意**：HITL 是天然 turn 边界。`permission_ask` 后 EventBus 关闭该 turn 的 Sinks，前端流正常结束。确认后是新 turn，新的 SSE 流。这与现有行为完全一致，无需特殊处理。

### 5.5 多标签页

```
标签页A: POST /chat → 订阅 EventBus
标签页B: GET /subscribe?afterSeq=0 → 同时收到事件

两者独立订阅同一 EventBus 的 Sinks.Many（multicast），
互不影响。任一关闭不影响另一个。
```

### 5.6 agent 执行出错

```
agent error → handleEventAndEmit 中 catch
  → EventBus.emitSynthetic(error 帧)
  → lease.release()
  → EventBus.closeSession()
  → 所有 SSE 订阅者收到 error 帧 + onComplete
```

---

## 6. 多实例扩展（P2，预留接口）

当前设计为单实例 `ConcurrentHashMap<String, Sinks.Many>`。多实例部署时需替换为 Redis Pub/Sub：

```java
// 预留接口
public interface SessionEventBus {
    void emit(String sessionId, AgentEvent event, String replyId);
    void emitSynthetic(String sessionId, String replyId, String type, String payload);
    Flux<ServerSentEvent<String>> subscribe(String sessionId, int afterSeq, String replyId);
    void closeSession(String sessionId);
    TurnStatus turnStatus(String sessionId);
}

// 当前实现：InProcessSessionEventBus
// P2 实现：RedisSessionEventBus（emit → Redis publish，subscribe → Redis subscribe + 本地 Sinks 扇出）
```

替换时仅需更换 Bean 实现，控制器零改动。

---

## 7. 兼容性与清理

| 项 | 处置 |
|---|------|
| SSE 词表 | 零改动（`AgentEventSseSerializer` 不动） |
| 前端 handleEvent | 零改动（data 格式不变） |
| POST /chat 响应 | SSE 格式不变，新增 `id` 字段（`seq` 值），前端可忽略 |
| GET /chat/stream（旧） | 保留不动 |
| confirm-stream | 保持 SSE 直吐模式（HITL 确认后是新 turn，不需续传） |
| SessionEventBus 类 | 原设计已删除，现在重新引入但职责不同（解耦而非长连接） |

---

## 8. 实施步骤

### 阶段 1：后端核心（约 2 天）

1. 新建 `SessionEventStore`：session_event 表 + CRUD
2. 新建 `SessionEventBus`：ConcurrentHashMap + Sinks + 心跳 + 回放
3. 改造 `SessionStreamController`：
   - `handleEventAndEmit()` 改为写入 EventBus
   - `onCancel()` 移除 `agentSubscription.dispose()`
   - 心跳从仅排队期扩展到全程
4. 新增 `GET /subscribe` 和 `GET /status` 端点
5. `TurnLeaseStore` 新增 `isHeld()` 方法
6. `SessionCleanupService` 扩展 session_event 清理
7. 配置项注入

### 阶段 2：单测（约 1 天）

8. `SessionEventStoreTest`：CRUD + 回放游标
9. `SessionEventBusTest`：emit → 订阅者收到、心跳注入、closeSession → onComplete、回放
10. `SessionStreamControllerTest`：改造后用例适配
11. 刷新恢复场景集成测试

### 阶段 3：前端（约 1 天）

12. `api.js`：新增 `subscribeSession()`、`getSessionStatus()`
13. `chat.js`：`sendMessage` 记录 `lastEventId`；新增 `restoreSession()`
14. `state.js`：`currentSessionId` 持久化

### 阶段 4：联调验证（约 1 天）

15. 长任务（5 分钟+沙箱执行）不断连验证
16. 刷新恢复续传验证
17. HITL 暂停/恢复不受影响验证
18. 多标签页同时订阅验证
19. Nginx `proxy_read_timeout` 极端场景自动重连验证

---

## 9. 风险与缓解

| # | 风险 | 概率 | 影响 | 缓解 |
|---|------|------|------|------|
| R1 | session_event 表膨胀（长 turn 产生大量 TEXT_BLOCK_DELTA 行） | 中 | 磁盘/查询性能 | 7 天清理 + `seq` 索引高效分页；P2 可考虑合并小 delta 为大块 |
| R2 | EventBus 内存泄漏（session Sinks 未被清理） | 低 | OOM | `closeSession` 在 AGENT_END/error 时必定调用；额外增加 Sinks TTL 兜底（5min 无订阅者自动清理） |
| R3 | agent 执行异常中断（未触发 AGENT_END 也未触发 error） | 低 | Sinks 悬挂 + turn_lease 残留 | turn_lease TTL 60s 兜底释放锁；Sinks TTL 5min 兜底清理；前端 status 轮询检测异常状态 |
| R4 | 回放期间事件乱序（回放事件和实时事件交叉） | 低 | 前端渲染异常 | `subscribe()` 先完整回放再切换实时流（`concatWith`），保证顺序 |
| R5 | 多实例部署时 EventBus 不共享 | 确定 | 跨 Pod 订阅丢失 | 当前为已知限制，文档标明；P2 实现 RedisSessionEventBus |

---

## 10. 设计自检清单

- [x] P1（长任务断连）：心跳覆盖全程，不仅是排队期
- [x] P2（刷新杀任务）：onCancel 不再 dispose agent，仅取消 SSE 订阅
- [x] P3（无法续传）：新增 subscribe 端点 + 事件持久化 + lastEventId 游标回放
- [x] SSE 词表不变，前端渲染逻辑零改动
- [x] HITL 场景不受影响（turn 边界语义保留）
- [x] turn_lease 语义不变（仅覆盖活跃执行段）
- [x] 向后兼容（旧 /chat/stream 保留）
- [x] 单实例先行，多实例扩展接口预留

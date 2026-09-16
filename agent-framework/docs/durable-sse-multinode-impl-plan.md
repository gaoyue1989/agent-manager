# 多副本正确性与落库性能改造 · 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 SessionEventBus 相关链路在 N 副本部署下语义正确（不丢事件、不悬挂流、状态判定准确），同时把单 turn 的落库语句数从 ~4200 降到 ~10 量级。

**Architecture:** 确立并落实一条规则——**拥有执行的请求消费本地 sink（保证首 token 延迟），其他所有观察者走 DB 游标**。新增 `SessionEventTailer` 承担观察者路径（回放 + 轮询追赶 + 终止判定），`SessionEventBus` 退回为纯进程内扇出。写入侧用内存 seq 计数器省掉 `SELECT MAX(seq)`，用多值 INSERT 攒批省掉逐条往返，并保证"DB 中 seq 始终是连续前缀"这一不变量。

**Tech Stack:** Java 17、Spring Boot 3.3.5、Reactor（Flux/Sinks）、MySQL（`session_event` / `turn_lease`）、JUnit 5 + Mockito + StepVerifier、Maven。

**设计依据：** `docs/durable-sse-multinode-plan.md`（已评审通过）。任务编号与设计文档 §7 实施步骤的对应关系在每节标注。

**范围：** 服务端阶段 1 + 2 + 3。前端重连（阶段 4）另立排期，不在本计划内。

**关键约束（来自设计文档 §6 R4）：** 本计划全部完成且验证通过之前，**不得将 replicas 调至 >1**。

---

## 文件结构

| 文件 | 职责 | 动作 |
|------|------|------|
| `src/main/java/io/agentmanager/framework/service/SessionEventStore.java` | 事件持久化：seq 分配、攒批写入、分页回放 | 修改 |
| `src/main/java/io/agentmanager/framework/service/SessionEventBus.java` | 进程内扇出（执行副本自用）、sink 生命周期、sink 驱逐 | 修改 |
| `src/main/java/io/agentmanager/framework/service/SessionEventTailer.java` | **新增**：观察者路径——回放 + 游标追赶 + turn 终止判定 | 创建 |
| `src/main/java/io/agentmanager/framework/controller/SessionStreamController.java` | `GET /subscribe` / `GET /status` | 修改 |
| `src/main/java/io/agentmanager/framework/controller/ChatStreamController.java` | `POST /chat`：turn 开始播种 seq、HITL 关流 | 修改 |
| `src/main/java/io/agentmanager/framework/controller/ConfirmController.java` | `POST /confirm-stream`：turn 开始播种 seq、HITL 关流 | 修改 |
| `src/main/java/io/agentmanager/framework/config/AgentScopeConfig.java` | Bean 装配（EventBus 增加 TurnLeaseStore 依赖；新增 Tailer Bean） | 修改 |
| `src/main/java/io/agentmanager/framework/service/SessionCleanupService.java` | 每日清理：移除 `evictStaleSinks` 调用（改为独立调度） | 修改 |
| `src/test/java/.../service/SessionEventStoreTest.java` | 单测 | 修改 |
| `src/test/java/.../service/SessionEventBusTest.java` | 单测 | 修改 |
| `src/test/java/.../service/SessionEventTailerTest.java` | **新增**：单测 + 跨副本场景集成测试 | 创建 |

**测试命令约定：** 单测 `mvn test -Dtest=<ClassName>`；全量 `mvn test`。

---

## Task 1: SessionEventStore — seq 内存计数器

**对应设计文档：** §3.2.1（阶段 1 第 1 项）

**解决的问题：** F8。每次 `append` 都要 `SELECT COALESCE(MAX(seq),0)`，一个 2000-token 的 turn 多出 2100 条 SELECT。

**Files:**
- Modify: `src/main/java/io/agentmanager/framework/service/SessionEventStore.java`
- Test: `src/test/java/io/agentmanager/framework/service/SessionEventStoreTest.java`

- [ ] **Step 1: 写失败测试**

在 `SessionEventStoreTest` 中追加（放在 `appendReturnsMinusOneOnFailure` 之后）：

```java
    @Test
    void seedSeqThenAppendDoesNotQueryMaxAgain() throws Exception {
        var conn = mock(java.sql.Connection.class);
        var selectPs = mock(java.sql.PreparedStatement.class);
        var insertPs = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(contains("SELECT COALESCE(MAX"))).thenReturn(selectPs);
        when(selectPs.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(5);
        when(conn.prepareStatement(contains("INSERT INTO session_event"))).thenReturn(insertPs);
        when(insertPs.executeUpdate()).thenReturn(1);

        store.seedSeq("sid-counter");
        assertEquals(6, store.append("sid-counter", "rid-1", "TOOL_CALL_START", "{}"));
        assertEquals(7, store.append("sid-counter", "rid-1", "TOOL_CALL_START", "{}"));

        // SELECT MAX 只应在 seedSeq 时执行一次
        verify(conn, times(1)).prepareStatement(contains("SELECT COALESCE(MAX"));
    }

    @Test
    void seedSeqIsIdempotent() throws Exception {
        var conn = mock(java.sql.Connection.class);
        var selectPs = mock(java.sql.PreparedStatement.class);
        var insertPs = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(contains("SELECT COALESCE(MAX"))).thenReturn(selectPs);
        when(selectPs.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(5);
        when(conn.prepareStatement(contains("INSERT INTO session_event"))).thenReturn(insertPs);
        when(insertPs.executeUpdate()).thenReturn(1);

        store.seedSeq("sid-idem");
        store.append("sid-idem", "rid-1", "TOOL_CALL_START", "{}");   // seq=6
        store.seedSeq("sid-idem");                                     // 重复播种不得回退
        assertEquals(7, store.append("sid-idem", "rid-1", "TOOL_CALL_START", "{}"));

        verify(conn, times(1)).prepareStatement(contains("SELECT COALESCE(MAX"));
    }

    @Test
    void appendFallsBackToDbWhenNotSeeded() throws Exception {
        var conn = mock(java.sql.Connection.class);
        var selectPs = mock(java.sql.PreparedStatement.class);
        var insertPs = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(contains("SELECT COALESCE(MAX"))).thenReturn(selectPs);
        when(selectPs.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(41);
        when(conn.prepareStatement(contains("INSERT INTO session_event"))).thenReturn(insertPs);
        when(insertPs.executeUpdate()).thenReturn(1);

        // 未播种：退回 DB 查询，保持既有语义
        assertEquals(42, store.append("sid-unseeded", "rid-1", "TOOL_CALL_START", "{}"));
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=SessionEventStoreTest`
Expected: 编译失败 —— `cannot find symbol: method seedSeq(java.lang.String)`

- [ ] **Step 3: 实现**

在 `SessionEventStore` 的字段区追加（`retentionDays` 之后）：

```java
    /**
     * session_id → 下一个待分配的 seq。
     *
     * <p>seq 是 session 维度递增的，而 turn 会跨副本交接（HITL 恢复产生新 turn、
     * 新 replyId，可能在另一个 Pod 上执行）。因此计数器必须在**每个 turn 开始时**
     * 从 DB 当前最大值续起，不能只在 Pod 启动时初始化一次。
     *
     * <p>安全性依赖：同一 session 的 turn 由 turn_lease 全局串行化，任一时刻只有一个
     * Pod 在写该 session。若该前提被放松，本计数器会产生 seq 冲突。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>
        seqCounters = new java.util.concurrent.ConcurrentHashMap<>();
```

在 `append` 方法**之前**插入三个方法：

```java
    /**
     * 播种 seq 计数器（turn 开始时调用，必须在本 Pod 获得 turn_lease 之后）。
     *
     * <p>幂等：计数器已存在时不做任何事，避免把已推进的计数器重置回 DB 的最大值
     * （缓冲区中尚未落库的行会因此被覆盖）。
     */
    public void seedSeq(String sessionId) {
        seqCounters.computeIfAbsent(sessionId, sid -> {
            int max = findMaxSeq(sid);
            log.debug("SessionEventStore: seeded seq counter for {} at {}", sid, max);
            return new java.util.concurrent.atomic.AtomicInteger(max);
        });
    }

    /** 释放 seq 计数器（turn 结束时调用，防止 map 无界增长） */
    public void releaseSeq(String sessionId) {
        seqCounters.remove(sessionId);
    }

    /** 取下一个 seq：已播种走内存计数器，未播种退回 DB 查询 */
    private int nextSeq(String sessionId) {
        var counter = seqCounters.get(sessionId);
        if (counter != null) {
            return counter.incrementAndGet();
        }
        return findMaxSeq(sessionId) + 1;
    }
```

将 `append` 方法体中的 seq 分配段替换：

```java
    public int append(String sessionId, String replyId, String eventType, String payload) {
        try (var conn = dataSource.getConnection()) {
            // 获取当前 session 的最大 seq，+1 作为新 seq
            // 同 session 由 turn_lease 串行化，无并发竞争
            int nextSeq;
            try (var ps = conn.prepareStatement(
                    "SELECT COALESCE(MAX(seq), 0) FROM session_event WHERE session_id = ?")) {
                ps.setString(1, sessionId);
                var rs = ps.executeQuery();
                rs.next();
                nextSeq = rs.getInt(1) + 1;
            }
```

替换为：

```java
    public int append(String sessionId, String replyId, String eventType, String payload) {
        try (var conn = dataSource.getConnection()) {
            int nextSeq = nextSeq(sessionId);
```

其余 INSERT 部分保持不变。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=SessionEventStoreTest`
Expected: PASS（全部 11 个用例：原有 8 个 + 新增 3 个）

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/agentmanager/framework/service/SessionEventStore.java src/test/java/io/agentmanager/framework/service/SessionEventStoreTest.java
git commit -m "perf(agent-framework): SessionEventStore 引入内存 seq 计数器，消除逐事件 SELECT MAX(seq)

对应 durable-sse-multinode-plan §3.2.1。seedSeq 幂等且需在获得 turn_lease
之后调用——seq 为 session 维度递增而 turn 会跨副本交接。"
```

---

## Task 2: SessionEventStore — 多值 INSERT 攒批

**对应设计文档：** §3.2.2、§3.1（I1 不变量）

**解决的问题：** F8 的语句数与连接借用次数。单 turn 从 ~2100 条 INSERT 降到 ~10 条。

**关键不变量 I1：** DB 中 `session_event` 的 seq 始终是**连续前缀**。做法是把里程碑行也塞进同一个批量 INSERT，与缓冲中的 delta 一起落库——要么整批成功（前缀连续），要么整批失败（前缀仍连续）。**不可**先单独插里程碑再补 delta。

**Files:**
- Modify: `src/main/java/io/agentmanager/framework/service/SessionEventStore.java`
- Test: `src/test/java/io/agentmanager/framework/service/SessionEventStoreTest.java`

- [ ] **Step 1: 写失败测试**

在 `SessionEventStoreTest` 追加：

```java
    @Test
    void deltasAreBufferedUntilMilestone() throws Exception {
        var conn = mock(java.sql.Connection.class);
        var selectPs = mock(java.sql.PreparedStatement.class);
        var insertPs = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(contains("SELECT COALESCE(MAX"))).thenReturn(selectPs);
        when(selectPs.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(0);
        when(conn.prepareStatement(startsWith("INSERT INTO session_event"))).thenReturn(insertPs);
        when(insertPs.executeUpdate()).thenReturn(1);

        store.seedSeq("sid-buf");
        store.append("sid-buf", "r", "TEXT_BLOCK_DELTA", "{\"delta\":\"a\"}");
        store.append("sid-buf", "r", "TEXT_BLOCK_DELTA", "{\"delta\":\"b\"}");

        // 纯 delta：不落库
        verify(insertPs, never()).executeUpdate();
    }

    @Test
    void milestoneFlushesBufferedDeltasInSameStatement() throws Exception {
        var conn = mock(java.sql.Connection.class);
        var selectPs = mock(java.sql.PreparedStatement.class);
        var insertPs = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(contains("SELECT COALESCE(MAX"))).thenReturn(selectPs);
        when(selectPs.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(0);
        when(conn.prepareStatement(startsWith("INSERT INTO session_event"))).thenReturn(insertPs);
        when(insertPs.executeUpdate()).thenReturn(1);

        store.seedSeq("sid-buf2");
        store.append("sid-buf2", "r", "TEXT_BLOCK_DELTA", "{\"delta\":\"a\"}");
        store.append("sid-buf2", "r", "TEXT_BLOCK_DELTA", "{\"delta\":\"b\"}");
        store.append("sid-buf2", "r", "AGENT_END", "{}");

        // 捕获所有 prepareStatement 调用（seedSeq 会先发一次 SELECT MAX），取最后一次即 INSERT
        var sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(conn, atLeastOnce()).prepareStatement(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        assertTrue(sql.startsWith("INSERT INTO session_event"), "实际 SQL: " + sql);
        // 3 行数据 → 3 组 (?,?,?,?,?,NOW(3))
        long groups = sql.split("\\?", -1).length / 5;
        assertEquals(3, groups, "期望 3 组 VALUES，实际 SQL: " + sql);
        verify(insertPs, times(1)).executeUpdate();
    }

    @Test
    void finishTurnFlushesPendingRows() throws Exception {
        var conn = mock(java.sql.Connection.class);
        var selectPs = mock(java.sql.PreparedStatement.class);
        var insertPs = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(contains("SELECT COALESCE(MAX"))).thenReturn(selectPs);
        when(selectPs.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(0);
        when(conn.prepareStatement(startsWith("INSERT INTO session_event"))).thenReturn(insertPs);
        when(insertPs.executeUpdate()).thenReturn(1);

        store.seedSeq("sid-fin");
        store.append("sid-fin", "r", "TEXT_BLOCK_DELTA", "{\"delta\":\"tail\"}");
        store.finishTurn("sid-fin");

        verify(insertPs, times(1)).executeUpdate();

        // 计数器已释放：下一次 append 退回 DB 查询（SELECT MAX 再执行一次）
        store.append("sid-fin", "r", "TEXT_BLOCK_DELTA", "{\"delta\":\"later\"}");
        verify(conn, times(2)).prepareStatement(contains("SELECT COALESCE(MAX"));
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=SessionEventStoreTest`
Expected: 编译失败 —— `cannot find symbol: method finishTurn(java.lang.String)`

- [ ] **Step 3: 实现**

在 `SessionEventStore` 字段区追加：

```java
    /** delta 类事件：进缓冲攒批，不逐条落库 */
    private static final java.util.Set<String> DELTA_EVENT_TYPES =
        java.util.Set.of("TEXT_BLOCK_DELTA", "THINKING_BLOCK_DELTA");

    /** 默认批量大小（行数） */
    private static final int DEFAULT_BATCH_SIZE = 200;

    /** 默认攒批时间窗（毫秒）：超过则把缓冲刷出，限制重连回放的滞后 */
    private static final int DEFAULT_FLUSH_INTERVAL_MS = 1000;

    private final int batchSize;
    private final int flushIntervalMs;

    /** 待落库的行（delta 缓冲）；访问一律持 pending 监视器 */
    private final List<PendingRow> pending = new java.util.ArrayList<>();

    private long lastFlushAt = System.currentTimeMillis();

    /** 攒批缓冲中的一行（尚未落库） */
    private record PendingRow(String sessionId, int seq, String replyId,
                              String eventType, String payload) {}
```

构造函数改为三个并存：

```java
    public SessionEventStore(DataSource dataSource) {
        this(dataSource, 7, DEFAULT_BATCH_SIZE, DEFAULT_FLUSH_INTERVAL_MS);
    }

    public SessionEventStore(DataSource dataSource, int retentionDays) {
        this(dataSource, retentionDays, DEFAULT_BATCH_SIZE, DEFAULT_FLUSH_INTERVAL_MS);
    }

    public SessionEventStore(DataSource dataSource, int retentionDays,
                             int batchSize, int flushIntervalMs) {
        this.dataSource = dataSource;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
        this.flushIntervalMs = flushIntervalMs >= 0 ? flushIntervalMs : DEFAULT_FLUSH_INTERVAL_MS;
        initSchema();
    }
```

> 注意：现有两个构造函数 `SessionEventStore(DataSource)` 与 `SessionEventStore(DataSource, int)` 的原有实现需要一并替换为上面的委托形式，避免重复赋值。

将 `append` 整体替换为：

```java
    /**
     * 追加一条事件记录，返回分配的 seq。
     *
     * <p>delta 类事件（TEXT_BLOCK_DELTA / THINKING_BLOCK_DELTA）进缓冲攒批；里程碑事件
     * 触发一次 flush，把缓冲与自身放在**同一条多值 INSERT** 中落库。
     *
     * <p>这保证了不变量 I1：DB 中的 seq 始终是连续前缀——整批成功则前缀连续，
     * 整批失败则前缀未被破坏（不会出现"里程碑已落库但中间的 delta 缺失"）。
     *
     * @return 分配的 seq（从 1 开始递增）；-1 表示落库失败
     */
    public int append(String sessionId, String replyId, String eventType, String payload) {
        int seq = nextSeq(sessionId);
        var row = new PendingRow(sessionId, seq, replyId, eventType, payload);

        List<PendingRow> toFlush = null;
        synchronized (pending) {
            pending.add(row);
            long now = System.currentTimeMillis();
            boolean milestone = !DELTA_EVENT_TYPES.contains(eventType);
            boolean full = pending.size() >= batchSize;
            boolean timedOut = now - lastFlushAt >= flushIntervalMs;
            if (milestone || full || timedOut) {
                toFlush = new ArrayList<>(pending);
                pending.clear();
                lastFlushAt = now;
            }
        }

        if (toFlush != null && insertBatch(toFlush) < 0) {
            return -1;
        }
        return seq;
    }

    /**
     * turn 结束：把缓冲刷出并释放 seq 计数器。
     * 必须由 turn 的终态路径调用，否则尾部 delta 会一直留在内存中直到下一个 turn。
     */
    public void finishTurn(String sessionId) {
        flushPending();
        releaseSeq(sessionId);
    }

    /** 刷出缓冲中所有待落库的行 */
    public void flushPending() {
        List<PendingRow> toFlush;
        synchronized (pending) {
            if (pending.isEmpty()) return;
            toFlush = new ArrayList<>(pending);
            pending.clear();
            lastFlushAt = System.currentTimeMillis();
        }
        insertBatch(toFlush);
    }

    /**
     * 一条多值 INSERT 写入多行。
     *
     * @return 受影响行数；< 0 表示失败
     */
    private int insertBatch(List<PendingRow> rows) {
        if (rows.isEmpty()) return 0;
        var sql = new StringBuilder(
            "INSERT INTO session_event (session_id, seq, event_type, payload, reply_id, created_at) VALUES ");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sql.append(',');
            sql.append("(?,?,?,?,?,NOW(3))");
        }
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            for (var r : rows) {
                ps.setString(idx++, r.sessionId());
                ps.setInt(idx++, r.seq());
                ps.setString(idx++, r.eventType());
                ps.setString(idx++, r.payload());
                ps.setString(idx++, r.replyId());
            }
            return ps.executeUpdate();
        } catch (Exception e) {
            log.error("SessionEventStore: batch insert failed ({} rows, first session={}): {}",
                rows.size(), rows.get(0).sessionId(), e.getMessage());
            // 持久化失败不应阻塞主链路——EventBus 仍可广播实时事件
            return -1;
        }
    }
```

删除原 `append` 中的两条 `prepareStatement` 逻辑（已被上面的实现取代）。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=SessionEventStoreTest`
Expected: PASS（全部 14 个用例：Task 1 后为 11 个 + 本次新增 3 个）

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/agentmanager/framework/service/SessionEventStore.java src/test/java/io/agentmanager/framework/service/SessionEventStoreTest.java
git commit -m "perf(agent-framework): SessionEventStore 改用多值 INSERT 攒批落库

对应 durable-sse-multinode-plan §3.2.2。delta 进缓冲，里程碑事件把缓冲与
自身放在同一条多值 INSERT 中落库，保证不变量 I1（DB 中 seq 始终是连续
前缀）——整批成功则连续，整批失败则前缀未破坏。单 turn 语句数 ~2100 → ~10。"
```

---

## Task 3: SessionEventBus — evictStaleSinks 独立调度 + turn 生命周期接入

**对应设计文档：** §3.2.3

**解决的问题：** F2 的兜底。`evictStaleSinks()` 目前只在 `SessionCleanupService` 的每日 03:00 cron 中执行（`SessionCleanupService.java:68,82`），导致漏掉 `closeSession` 的 sink 会存活到次日凌晨。

同时把 `finishTurn`（Task 2 引入）接到 `closeSession` 上。

**Files:**
- Modify: `src/main/java/io/agentmanager/framework/service/SessionEventBus.java`
- Modify: `src/main/java/io/agentmanager/framework/service/SessionCleanupService.java`
- Test: `src/test/java/io/agentmanager/framework/service/SessionEventBusTest.java`

- [ ] **Step 1: 写失败测试**

在 `SessionEventBusTest` 追加：

```java
    @Test
    void closeSessionFinishesTurnOnStore() {
        eventBus.ensureSink("sid-finish");
        eventBus.closeSession("sid-finish");
        verify(eventStore).finishTurn("sid-finish");
    }

    @Test
    void beginTurnSeedsSeqAndCreatesSink() {
        var sink = eventBus.beginTurn("sid-begin");
        assertNotNull(sink);
        verify(eventStore).seedSeq("sid-begin");
        assertSame(sink, eventBus.ensureSink("sid-begin"));
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=SessionEventBusTest`
Expected: 编译失败 —— `cannot find symbol: method beginTurn(java.lang.String)`

- [ ] **Step 3: 实现**

在 `SessionEventBus` 中，`ensureSink` 之后新增 `beginTurn`：

```java
    /**
     * 开始一个 turn：播种 seq 计数器 + 确保 sink 存在。
     *
     * <p>必须在**获得 turn_lease 之后**调用——seq 计数器要从 DB 当前最大值续起
     * （见 SessionEventStore.seedSeq）。两个动作合在一起是因为它们同属
     * "为本 turn 准备输出通道与序号空间"这一件事。
     */
    public Sinks.Many<SessionEventStore.EnvelopedEvent> beginTurn(String sessionId) {
        eventStore.seedSeq(sessionId);
        return ensureSink(sessionId);
    }
```

`closeSession` 改为：

```java
    /**
     * turn 结束时关闭 session 的 Sinks。
     * 所有 SSE 订阅者收到 onComplete → 流关闭 → 前端显示完成。
     * 同时刷出待落库行并释放 seq 计数器。
     */
    public void closeSession(String sessionId) {
        eventStore.finishTurn(sessionId);
        var sink = sinks.remove(sessionId);
        lastActiveAt.remove(sessionId);
        if (sink != null) {
            sink.tryEmitComplete();
            log.info("[EventBus] session closed (sid={})", sessionId);
        }
    }
```

`evictStaleSinks` 加上独立调度注解（在方法上增加一行 `@Scheduled`，并补 import）：

```java
    /**
     * 清理过期的 Sinks（无订阅者且超过 evictionDelay 未活跃）。
     *
     * <p>独立调度（60s 一次）——此前仅挂在 SessionCleanupService 的每日 03:00 cron 上，
     * 导致任何漏掉 closeSession 的 sink 会存活近 24 小时，跨副本 subscribe 也会
     * 因此悬挂同样长的时间。见 durable-sse-multinode-plan §3.2.3。
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000)
    public int evictStaleSinks() {
```

方法体不变。

从 `SessionCleanupService.cleanup()` 中删除 `sessionEventBus.evictStaleSinks();` 及其注释行（第 81-82 行）：

```java
        // 3. 清理 EventBus 中过期的 Sinks（无订阅者且超时未活跃）
        sessionEventBus.evictStaleSinks();
```

删除后，`cleanup()` 中原第 3 步之后的编号注释（4、5）需相应改为 3、4。

> `sessionEventBus` 字段在 `SessionCleanupService` 中若因此变为未使用，则一并删除该字段与构造参数，并同步修改 `AgentScopeConfig` 中的装配（若该 Service 由 Spring 自动装配则无需改动配置类）。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=SessionEventBusTest`
Expected: PASS

再跑全量确认清理侧没有编译/装配断裂：

Run: `mvn test -Dtest='SessionCleanupServiceTest,SessionEventBusTest,SessionEventStoreTest'`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/agentmanager/framework/service/SessionEventBus.java src/main/java/io/agentmanager/framework/service/SessionCleanupService.java src/test/java/io/agentmanager/framework/service/SessionEventBusTest.java
git commit -m "fix(agent-framework): evictStaleSinks 改为独立调度，并接入 turn 生命周期

对应 durable-sse-multinode-plan §3.2.3。此前 evictStaleSinks 只挂在每日
03:00 的 cron 上，漏掉 closeSession 的 sink 会存活近 24 小时。
closeSession 现同时刷出待落库行并释放 seq 计数器。"
```

---

## Task 4: SessionEventStore.queryAfter — 连接持有修正 + LIMIT + 分页

**对应设计文档：** §3.3.1

**解决的问题：** F6。`queryAfter` 用 `try (conn)` 包住整个推送循环，慢客户端触发反压时连接被钉住；连接池默认仅 10，10 个并发回放即可耗尽全 Pod 的连接池。且当前查询无 `LIMIT`，一次回放 2000 行 ~1MB 全量 materialize。

**Files:**
- Modify: `src/main/java/io/agentmanager/framework/service/SessionEventStore.java`
- Test: `src/test/java/io/agentmanager/framework/service/SessionEventStoreTest.java`

- [ ] **Step 1: 写失败测试**

在 `SessionEventStoreTest` 追加：

```java
    @Test
    void queryAfterReleasesConnectionBeforeEmitting() throws Exception {
        // 用独立连接 mock——setUp 里的 conn 已被 initSchema 关闭过一次，
        // 复用会让 verify(conn).close() 的计数失真
        var queryConn = mock(java.sql.Connection.class);
        var ps = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);

        when(dataSource.getConnection()).thenReturn(queryConn);
        when(queryConn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getInt("seq")).thenReturn(3);
        when(rs.getString("event_type")).thenReturn("TEXT_BLOCK_DELTA");
        when(rs.getString("payload")).thenReturn("{}");
        when(rs.getString("reply_id")).thenReturn("rid-1");

        var flux = store.queryAfter("sid-conn", "rid-1", 2);
        StepVerifier.create(flux)
            .expectNextMatches(e -> e.seq() == 3)
            .verifyComplete();

        // 关键：ResultSet / Statement / Connection 在事件被消费前就已关闭
        verify(rs).close();
        verify(ps).close();
        verify(queryConn).close();
    }

    @Test
    void queryAfterPagesThroughMultipleBatches() throws Exception {
        var queryConn = mock(java.sql.Connection.class);
        var ps = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);

        when(dataSource.getConnection()).thenReturn(queryConn);
        when(queryConn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);

        int pageSize = SessionEventStore.queryPageSize();
        // 第 1 页返回满页（pageSize 行）→ 触发第 2 次查询；第 2 页返回 0 行 → 结束
        var remaining = new java.util.concurrent.atomic.AtomicInteger(pageSize);
        when(rs.next()).thenAnswer(inv -> remaining.getAndDecrement() > 0);
        var seqs = new java.util.ArrayDeque<Integer>();
        for (int i = 1; i <= pageSize; i++) seqs.add(i);
        when(rs.getInt("seq")).thenAnswer(inv -> seqs.isEmpty() ? 1 : seqs.poll());
        when(rs.getString("event_type")).thenReturn("TEXT_BLOCK_DELTA");
        when(rs.getString("payload")).thenReturn("{}");
        when(rs.getString("reply_id")).thenReturn("rid-1");

        StepVerifier.create(store.queryAfter("sid-page", "rid-1", 0))
            .expectNextCount(pageSize)
            .verifyComplete();

        // 满页后应再发起一次查询（共 2 次）
        verify(queryConn, times(2)).prepareStatement(anyString());
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=SessionEventStoreTest`
Expected: 编译失败 —— `cannot find symbol: method queryPageSize()`

- [ ] **Step 3: 实现**

在 `SessionEventStore` 中新增常量与分页大小访问器：

```java
    /** 回放分页大小：限制单次查询 materialize 的行数 */
    private static final int QUERY_PAGE_SIZE = 500;

    /** 回放分页大小（测试可见） */
    static int queryPageSize() {
        return QUERY_PAGE_SIZE;
    }
```

将原 `queryAfter` 整体替换为：

```java
    /**
     * 查询 afterSeq 之后的事件（回放用）。
     *
     * <p>分页读取：每页在**独立连接内 materialize 成 List 后立即释放连接**，再从内存中
     * 向外发。避免慢客户端反压时把数据库连接钉在整个推送期间（连接池默认仅 10）。
     *
     * @param afterSeq 游标：返回 seq > afterSeq 的记录
     * @param replyId  turn 标识（可选）；null 表示不限 turn
     */
    public Flux<EnvelopedEvent> queryAfter(String sessionId, String replyId, int afterSeq) {
        return Flux.create(sink -> {
            int cursor = afterSeq;
            try {
                while (!sink.isCancelled()) {
                    var page = queryPage(sessionId, replyId, cursor, QUERY_PAGE_SIZE);
                    if (page.isEmpty()) break;
                    for (var e : page) {
                        if (sink.isCancelled()) return;
                        sink.next(e);
                        cursor = e.seq();
                    }
                    if (page.size() < QUERY_PAGE_SIZE) break;
                }
                if (!sink.isCancelled()) sink.complete();
            } catch (Exception e) {
                log.warn("SessionEventStore: queryAfter failed for {}: {}", sessionId, e.getMessage());
                sink.error(e);
            }
        });
    }

    /** 读取一页事件并 materialize 成 List（连接在方法返回前关闭） */
    private List<EnvelopedEvent> queryPage(String sessionId, String replyId, int afterSeq, int limit) {
        boolean byReply = replyId != null && !replyId.isBlank();
        String sql = byReply
            ? "SELECT seq, event_type, payload, reply_id FROM session_event "
                + "WHERE session_id = ? AND reply_id = ? AND seq > ? ORDER BY seq ASC LIMIT ?"
            : "SELECT seq, event_type, payload, reply_id FROM session_event "
                + "WHERE session_id = ? AND seq > ? ORDER BY seq ASC LIMIT ?";

        var out = new ArrayList<EnvelopedEvent>();
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, sessionId);
            if (byReply) ps.setString(i++, replyId);
            ps.setInt(i++, afterSeq);
            ps.setInt(i, limit);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new EnvelopedEvent(
                        rs.getInt("seq"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getString("reply_id")));
                }
            }
        } catch (Exception e) {
            log.warn("SessionEventStore: queryPage failed for {}: {}", sessionId, e.getMessage());
        }
        return out;
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=SessionEventStoreTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/agentmanager/framework/service/SessionEventStore.java src/test/java/io/agentmanager/framework/service/SessionEventStoreTest.java
git commit -m "fix(agent-framework): queryAfter 分页 materialize，不再把连接钉在推送期间

对应 durable-sse-multinode-plan §3.3.1（F6）。原实现用 try(conn) 包住整个
sink.next 循环，慢客户端反压下连接被占满整个回放过程；连接池默认 10，
10 个并发回放即可耗尽整 Pod 连接池。现每页在独立连接内读入 List 后立即
释放，并补 LIMIT 限制单次 materialize 的行数。"
```

---

## Task 5: 新增 SessionEventTailer（终止判定）

**对应设计文档：** §3.4.2

**解决的问题：** F1/F2 的判定基础。把"turn 是否结束/中断"的判定收敛到一处，供游标追赶与 `/status` 共用。判定基于 DB 的 `turn_lease` 与 `confirm_context`，天生跨副本正确。

**为什么新建一个类而不是塞进 SessionEventBus：** `SessionEventBus` 的职责是"进程内扇出"，观察者路径走 DB 不碰 sink；且判定需要 `AgentRuntimeService`（取 `findPendingConfirm`），把它引入 EventBus 会让 EventBus 依赖大幅变重。设计文档 §2.4 的规则（写入方消费本地 sink、观察者读 DB）在这个文件边界上体现。

**Files:**
- Create: `src/main/java/io/agentmanager/framework/service/SessionEventTailer.java`
- Test: `src/test/java/io/agentmanager/framework/service/SessionEventTailerTest.java`

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/io/agentmanager/framework/service/SessionEventTailerTest.java`：

```java
package io.agentmanager.framework.service;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SessionEventTailer 终止判定单测（durable-sse-multinode-plan §3.4.2）。
 *
 * <p>判定表：
 * <ul>
 *   <li>lease 被持有 → running</li>
 *   <li>lease 释放 + 最新事件终态（AGENT_END / error）→ finished</li>
 *   <li>lease 释放 + 有待确认上下文 → finished（HITL 是 turn 边界）</li>
 *   <li>lease 释放 + 最新事件非终态 + 无待确认 → interrupted</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SessionEventTailerTest {

    @Mock
    private SessionEventStore eventStore;

    @Mock
    private TurnLeaseStore turnLeaseStore;

    @Mock
    private AgentRuntimeService runtimeService;

    private SessionEventTailer tailer;

    @BeforeEach
    void setUp() {
        tailer = new SessionEventTailer(eventStore, turnLeaseStore, runtimeService,
            Duration.ofMillis(50));
    }

    @Test
    void runningWhenLeaseHeld() {
        when(turnLeaseStore.isHeld("sid-1")).thenReturn(true);
        var probe = tailer.probe("sid-1");
        assertTrue(probe.running());
        assertFalse(probe.finished());
        assertFalse(probe.interrupted());
        // lease 被持有时不应产生额外查询
        verifyNoInteractions(eventStore);
    }

    @Test
    void finishedWhenLatestIsAgentEnd() {
        when(turnLeaseStore.isHeld("sid-2")).thenReturn(false);
        when(eventStore.findLatest("sid-2")).thenReturn(
            new SessionEventStore.EnvelopedEvent(10, "AGENT_END", "{}", "rid"));
        var probe = tailer.probe("sid-2");
        assertTrue(probe.finished());
        assertFalse(probe.interrupted());
    }

    @Test
    void finishedWhenHitlPendingConfirm() {
        when(turnLeaseStore.isHeld("sid-3")).thenReturn(false);
        when(eventStore.findLatest("sid-3")).thenReturn(
            new SessionEventStore.EnvelopedEvent(7, "permission_ask", "{}", "rid"));
        when(runtimeService.findPendingConfirm("sid-3")).thenReturn(java.util.Map.of("tools", "[]"));
        var probe = tailer.probe("sid-3");
        assertTrue(probe.finished());
        assertFalse(probe.interrupted());
    }

    @Test
    void interruptedWhenNoLeaseNoPendingNoTerminal() {
        when(turnLeaseStore.isHeld("sid-4")).thenReturn(false);
        when(eventStore.findLatest("sid-4")).thenReturn(
            new SessionEventStore.EnvelopedEvent(7, "TEXT_BLOCK_DELTA", "{}", "rid"));
        when(runtimeService.findPendingConfirm("sid-4")).thenReturn(null);
        var probe = tailer.probe("sid-4");
        assertTrue(probe.interrupted());
        assertFalse(probe.finished());
    }

    @Test
    void finishedWhenNoEventsAtAll() {
        when(turnLeaseStore.isHeld("sid-5")).thenReturn(false);
        when(eventStore.findLatest("sid-5")).thenReturn(null);
        var probe = tailer.probe("sid-5");
        assertTrue(probe.finished());
        assertFalse(probe.interrupted());
    }

    @Test
    void probeWithPreFetchedDataUsesThem() {
        when(turnLeaseStore.isHeld("sid-6")).thenReturn(false);
        var latest = new SessionEventStore.EnvelopedEvent(3, "AGENT_END", "{}", "rid");
        var probe = tailer.probe("sid-6", latest, false);
        assertTrue(probe.finished());
        // 不应再自行查询
        verifyNoInteractions(eventStore, runtimeService);
    }

    @Test
    void terminalErrorTypeCountsAsFinished() {
        when(turnLeaseStore.isHeld("sid-7")).thenReturn(false);
        when(eventStore.findLatest("sid-7")).thenReturn(
            new SessionEventStore.EnvelopedEvent(4, "error", "{}", "rid"));
        assertTrue(tailer.probe("sid-7").finished());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=SessionEventTailerTest`
Expected: 编译失败 —— `cannot find symbol: class SessionEventTailer`

- [ ] **Step 3: 实现**

创建 `src/main/java/io/agentmanager/framework/service/SessionEventTailer.java`：

```java
package io.agentmanager.framework.service;

import java.time.Duration;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话事件追赶器（durable-sse-multinode-plan §2.4 / §3.4）。
 *
 * <p>承担**观察者路径**：被订阅的 session 的执行可能发生在另一个 Pod 上，因此这里
 * 不读任何进程内状态，只读 Pod 间共享的 DB——{@code session_event}（事件流）
 * 与 {@code turn_lease} / {@code confirm_context}（turn 状态）。
 *
 * <p>与之相对，{@link SessionEventBus} 只服务"拥有执行的那个请求自己的 SSE"，
 * 保证首 token 延迟不受影响。这条分工即设计文档的不变量 I4。
 */
public class SessionEventTailer {

    private static final Logger log = LoggerFactory.getLogger(SessionEventTailer.class);

    /** 终态事件类型：出现即代表 turn 不会再产出新事件 */
    private static final Set<String> TERMINAL_TYPES = Set.of("AGENT_END", "error");

    /** 终止探测的降频间隔：空闲时 ~2s 一次，避免每轮轮询都查 lease + confirm */
    private static final long PROBE_INTERVAL_MS = 2000;

    private final SessionEventStore eventStore;
    private final TurnLeaseStore turnLeaseStore;
    private final AgentRuntimeService runtimeService;

    /** 轮询间隔：无新事件时的查询节奏 */
    private final Duration pollInterval;

    public SessionEventTailer(SessionEventStore eventStore,
                              TurnLeaseStore turnLeaseStore,
                              AgentRuntimeService runtimeService,
                              Duration pollInterval) {
        this.eventStore = eventStore;
        this.turnLeaseStore = turnLeaseStore;
        this.runtimeService = runtimeService;
        this.pollInterval = pollInterval;
    }

    /** turn 状态探测结果 */
    public record TurnProbe(boolean running, boolean finished, boolean interrupted) {
        public static final TurnProbe RUNNING = new TurnProbe(true, false, false);
        public static final TurnProbe FINISHED = new TurnProbe(false, true, false);
        public static final TurnProbe INTERRUPTED = new TurnProbe(false, false, true);
    }

    /**
     * 完整探测：自行取数。
     *
     * <p>顺序刻意如此——lease 被持有时即可短路，不查事件与确认上下文。
     */
    public TurnProbe probe(String sessionId) {
        if (turnLeaseStore.isHeld(sessionId)) {
            return TurnProbe.RUNNING;
        }
        var latest = eventStore.findLatest(sessionId);
        if (latest == null || TERMINAL_TYPES.contains(latest.type())) {
            return TurnProbe.FINISHED;
        }
        // HITL 暂停点：lease 已让出但 turn 未结束。permission_ask 是 turn 边界，
        // 因此对观察者而言同样是"可以正常关流"（设计文档 §3.4.4 的决策）。
        if (runtimeService.findPendingConfirm(sessionId) != null) {
            return TurnProbe.FINISHED;
        }
        // 有事件、非终态、无租约、无待确认：执行副本已崩溃或被抢占
        log.debug("SessionEventTailer: turn interrupted (sid={}, latestSeq={})",
            sessionId, latest.seq());
        return TurnProbe.INTERRUPTED;
    }

    /** 用已取好的数据探测，避免重复查询（/status 端点用） */
    public TurnProbe probe(String sessionId, SessionEventStore.EnvelopedEvent latest,
                           boolean hasPendingConfirm) {
        if (turnLeaseStore.isHeld(sessionId)) {
            return TurnProbe.RUNNING;
        }
        if (latest == null || TERMINAL_TYPES.contains(latest.type())) {
            return TurnProbe.FINISHED;
        }
        if (hasPendingConfirm) {
            return TurnProbe.FINISHED;
        }
        return TurnProbe.INTERRUPTED;
    }
}
```

> 分页大小由 `SessionEventStore.QUERY_PAGE_SIZE` 统一控制（Task 4），此处不重复配置。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=SessionEventTailerTest`
Expected: PASS（7 个用例）

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/agentmanager/framework/service/SessionEventTailer.java src/test/java/io/agentmanager/framework/service/SessionEventTailerTest.java
git commit -m "feat(agent-framework): 新增 SessionEventTailer 与 turn 终止判定

对应 durable-sse-multinode-plan §3.4.2。判定只读 Pod 间共享的 DB
（turn_lease + confirm_context + session_event），天生跨副本正确。
HITL 暂停点按 turn 边界处理（§3.4.4 决策）。"
```

---

## Task 6: /status 改用 probe，新增 interrupted 态

**对应设计文档：** §3.4.3、§3.3.2

**解决的问题：** F3/F4/F7。`turnStatus` 的 `sinks.containsKey()` 是 Pod 本地判据（跨副本把 working 误报为 IDLE；本 Pod 残留 sink 又把 IDLE 误报为 WORKING，且 `/status` 的 `||` 语义让本地误报赢过 `leaseHeld=false`）。同时去掉 `findMaxSeq` 的重复查询。

**行为变化：** Pod 死亡后 lease 过期（TTL 60s），此前 `/status` 返回 `idle`——用户看到自己发了消息、没有回复、也没有报错。现在返回 `interrupted`。

**Files:**
- Modify: `src/main/java/io/agentmanager/framework/controller/SessionStreamController.java`
- Modify: `src/main/java/io/agentmanager/framework/config/AgentScopeConfig.java`
- Test: `src/test/java/io/agentmanager/framework/controller/SessionStreamControllerStatusTest.java`（新建）

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/io/agentmanager/framework/controller/SessionStreamControllerStatusTest.java`：

```java
package io.agentmanager.framework.controller;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.SessionEventBus;
import io.agentmanager.framework.service.SessionEventStore;
import io.agentmanager.framework.service.SessionEventTailer;
import io.agentmanager.framework.service.TurnLeaseStore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionStreamControllerStatusTest {

    @Mock private TurnLeaseStore turnLeaseStore;
    @Mock private AgentRuntimeService runtimeService;
    @Mock private SessionEventBus eventBus;
    @Mock private SessionEventStore eventStore;

    private SessionEventTailer tailer;
    private SessionStreamController controller;

    @BeforeEach
    void setUp() {
        tailer = new SessionEventTailer(eventStore, turnLeaseStore, runtimeService,
            Duration.ofMillis(300));
        controller = new SessionStreamController(runtimeService, turnLeaseStore, eventBus, eventStore, tailer);
    }

    @Test
    void interruptedWhenLeaseGoneAndNoTerminalEvent() {
        when(turnLeaseStore.isHeld("sid-1")).thenReturn(false);
        when(eventStore.findLatest("sid-1")).thenReturn(
            new SessionEventStore.EnvelopedEvent(9, "TEXT_BLOCK_DELTA", "{}", "rid-1"));
        when(runtimeService.findPendingConfirm("sid-1")).thenReturn(null);

        Map<String, Object> body = controller.status("sid-1");
        assertEquals("interrupted", body.get("state"));
        assertEquals(9, body.get("latest_event_seq"));
    }

    @Test
    void workingWhenLeaseHeldEvenWithoutLocalSink() {
        when(turnLeaseStore.isHeld("sid-2")).thenReturn(true);
        when(eventStore.findLatest("sid-2")).thenReturn(
            new SessionEventStore.EnvelopedEvent(3, "TEXT_BLOCK_DELTA", "{}", "rid-2"));
        when(runtimeService.findPendingConfirm("sid-2")).thenReturn(null);

        Map<String, Object> body = controller.status("sid-2");
        assertEquals("working", body.get("state"));
        // 跨副本关键点：本 Pod 没有 sink，也必须报 working
        verify(eventBus, never()).turnStatus(anyString());
    }

    @Test
    void completedWhenLatestIsAgentEnd() {
        when(turnLeaseStore.isHeld("sid-3")).thenReturn(false);
        when(eventStore.findLatest("sid-3")).thenReturn(
            new SessionEventStore.EnvelopedEvent(12, "AGENT_END", "{}", "rid-3"));
        when(runtimeService.findPendingConfirm("sid-3")).thenReturn(null);

        Map<String, Object> body = controller.status("sid-3");
        assertEquals("completed", body.get("state"));
        assertEquals(12, body.get("latest_event_seq"));
    }

    @Test
    void waitingConfirmTakesPrecedence() {
        when(eventStore.findLatest("sid-4")).thenReturn(
            new SessionEventStore.EnvelopedEvent(5, "permission_ask", "{}", "rid-4"));
        when(runtimeService.findPendingConfirm("sid-4")).thenReturn(Map.of("tools", "[]"));

        Map<String, Object> body = controller.status("sid-4");
        assertEquals("waiting_confirm", body.get("state"));
    }

    @Test
    void idleWhenNoEvents() {
        when(turnLeaseStore.isHeld("sid-5")).thenReturn(false);
        when(eventStore.findLatest("sid-5")).thenReturn(null);
        when(runtimeService.findPendingConfirm("sid-5")).thenReturn(null);

        Map<String, Object> body = controller.status("sid-5");
        assertEquals("idle", body.get("state"));
        assertEquals(0, body.get("latest_event_seq"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=SessionStreamControllerStatusTest`
Expected: 编译失败 —— `SessionStreamController` 构造器参数不匹配（尚为 4 参）

- [ ] **Step 3: 实现**

修改 `SessionStreamController`：构造函数增加 `SessionEventTailer tailer` 参数与字段。

```java
    private final TurnLeaseStore turnLeaseStore;
    private final AgentRuntimeService runtimeService;
    private final SessionEventBus eventBus;
    private final SessionEventStore eventStore;
    private final SessionEventTailer tailer;

    public SessionStreamController(AgentRuntimeService runtimeService,
                                   TurnLeaseStore turnLeaseStore,
                                   SessionEventBus eventBus,
                                   SessionEventStore eventStore,
                                   SessionEventTailer tailer) {
        this.runtimeService = runtimeService;
        this.turnLeaseStore = turnLeaseStore;
        this.eventBus = eventBus;
        this.eventStore = eventStore;
        this.tailer = tailer;
    }
```

`status` 方法整体替换为：

```java
    /**
     * 查询 session 当前 turn 状态（前端刷新恢复用）。
     *
     * <p>状态判定全部基于 Pod 间共享的 DB 状态（turn_lease / confirm_context /
     * session_event），不使用任何进程内状态——跨副本部署下本 Pod 可能从未执行过该
     * session，也可能残留过期的本地 sink。
     *
     * <p>响应示例：
     * <pre>{@code
     * {
     *   "session_id": "...",
     *   "state": "working",          // working / completed / waiting_confirm / interrupted / idle
     *   "latest_event_seq": 42,
     *   "reply_id": "...",
     *   "pending_confirm": null      // 或 HITL 确认上下文
     * }
     * }</pre>
     */
    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> status(@PathVariable String sessionId) {
        sessionId = io.agentmanager.framework.util.PathSafe.sanitize(sessionId);
        var pendingConfirm = runtimeService.findPendingConfirm(sessionId);
        var latestEvent = eventStore.findLatest(sessionId);
        var probe = tailer.probe(sessionId, latestEvent, pendingConfirm != null);

        String state;
        if (pendingConfirm != null) {
            state = "waiting_confirm";
        } else if (probe.running()) {
            state = "working";
        } else if (latestEvent != null && "AGENT_END".equals(latestEvent.type())) {
            state = "completed";
        } else if (probe.interrupted()) {
            // 有事件、非终态、无租约、无待确认：执行副本崩溃或被抢占
            state = "interrupted";
        } else {
            state = "idle";
        }

        return Map.of(
            "session_id", sessionId,
            "state", state,
            "latest_event_seq", latestEvent != null ? latestEvent.seq() : 0,
            "reply_id", latestEvent != null && latestEvent.replyId() != null
                ? latestEvent.replyId() : "",
            "pending_confirm", pendingConfirm != null ? pendingConfirm : ""
        );
    }
```

> `latest_event_seq` 直接取 `latestEvent.seq()`——`ORDER BY seq DESC LIMIT 1` 的那一行就是最大 seq，原来的 `findMaxSeq` 查询是重复的。若 `latestEvent` 为 null 则回落到 0（与既有语义一致）。
>
> `reply_id` 不再回落到 `eventBus.currentReplyId(sessionId)`——那是又一次 DB 查询，且 `latestEvent` 已提供相同信息。

在 `AgentScopeConfig` 中新增 Tailer Bean（放在 `sessionEventBus` Bean 之后）：

```java
    @Bean
    public io.agentmanager.framework.service.SessionEventTailer sessionEventTailer(
            io.agentmanager.framework.service.SessionEventStore sessionEventStore,
            io.agentmanager.framework.service.TurnLeaseStore turnLeaseStore,
            io.agentmanager.framework.service.AgentRuntimeService agentRuntimeService,
            AgentManagerProperties props) {
        var sse = props.sse();
        var poll = sse != null ? java.time.Duration.ofMillis(sse.tailPollMs())
                               : java.time.Duration.ofMillis(300);
        return new io.agentmanager.framework.service.SessionEventTailer(
            sessionEventStore, turnLeaseStore, agentRuntimeService, poll);
    }
```

在 `AgentManagerProperties.SseConfig` 中新增两个字段：

```java
    public record SseConfig(
            /** 心跳间隔（秒）：无业务事件时每 N 秒发一次 SSE comment 帧 */
            @DefaultValue("20") int heartbeatSeconds,
            /** Sinks 过期清理延迟（分钟）：无订阅者且超过该时长未活跃则回收 */
            @DefaultValue("5") int sinksEvictionMinutes,
            /** Sinks 缓冲区大小（每个 session） */
            @DefaultValue("256") int sinksBufferSize,
            /** 观察者游标轮询间隔（毫秒） */
            @DefaultValue("300") int tailPollMs
    ) {}
```

> `AgentScopeConfig` 中 `sessionEventBus` Bean 的装配暂不改动——Task 7 删除 `turnStatus` 后 `SessionEventBus` 不需要 `TurnLeaseStore`。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=SessionStreamControllerStatusTest`
Expected: PASS（5 个用例）

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/agentmanager/framework/controller/SessionStreamController.java src/main/java/io/agentmanager/framework/config/AgentScopeConfig.java src/main/java/io/agentmanager/framework/config/AgentManagerProperties.java src/test/java/io/agentmanager/framework/controller/SessionStreamControllerStatusTest.java
git commit -m "fix(agent-framework): /status 改用 DB 判据，新增 interrupted 态

对应 durable-sse-multinode-plan §3.4.3。原状态判定混用了进程内 sinks map
（turnStatus），跨副本下会把 working 误报为 IDLE；本 Pod 残留 sink 又会让
本地误报的 WORKING 赢过 leaseHeld=false。现全部基于 DB，并去掉重复的
findMaxSeq 查询（ORDER BY seq DESC LIMIT 1 已提供最大 seq）。"
```

---

## Task 7: subscribe 接入游标追赶，移除内部 sink 依赖

**对应设计文档：** §3.4.1

**解决的问题：** F1/F2/F4/F5。触发链是同一根：观察者路径去读**本 Pod 的 sink**。改为读 DB 后，跨副本不再丢事件、不再悬挂，多标签页的 replay→live 接缝也一并消失（游标连续，不再是"先回放完再订阅"两个动作）。同时 `subscribe()` 不再调用 `ensureSink()`，F4 从根上消失。

**Files:**
- Modify: `src/main/java/io/agentmanager/framework/service/SessionEventTailer.java`
- Modify: `src/main/java/io/agentmanager/framework/controller/SessionStreamController.java`
- Modify: `src/main/java/io/agentmanager/framework/service/SessionEventBus.java`（删除 `turnStatus` / `replayOnly` / `currentReplyId`）
- Test: `src/test/java/io/agentmanager/framework/service/SessionEventTailerTest.java`

- [ ] **Step 1: 写失败测试**

在 `SessionEventTailerTest` 追加：

```java
    @Test
    void tailReplaysThenEmitsDoneWhenTurnFinished() {
        // 回放取 seq>0 拿到 delta；追赶以游标 1 继续，拿到 AGENT_END → 补 done 帧
        when(eventStore.queryAfter("sid-t1", "rid", 0)).thenReturn(Flux.just(
            new SessionEventStore.EnvelopedEvent(1, "TEXT_BLOCK_DELTA",
                "{\"type\":\"TEXT_BLOCK_DELTA\",\"delta\":\"hi\"}", "rid")));
        when(eventStore.queryAfter("sid-t1", "rid", 1)).thenReturn(Flux.just(
            new SessionEventStore.EnvelopedEvent(2, "AGENT_END", "{}", "rid")));

        StepVerifier.create(tailer.tail("sid-t1", "rid", 0))
            .expectNextMatches(sse -> sse.data() != null && sse.data().contains("hi"))
            .expectNextMatches(sse -> sse.data() != null && sse.data().contains("done"))
            .verifyComplete();
    }

    @Test
    void tailEmitsInterruptedWhenExecutorCrashed() {
        // 回放拿到 seq=4 的 delta；追赶以游标 4 继续，无新事件
        // → 首轮立即探测：lease 已释放、最新非终态、无待确认 → interrupted
        when(eventStore.queryAfter("sid-t2", "rid", 0)).thenReturn(Flux.just(
            new SessionEventStore.EnvelopedEvent(4, "TEXT_BLOCK_DELTA",
                "{\"type\":\"TEXT_BLOCK_DELTA\",\"delta\":\"partial\"}", "rid")));
        when(eventStore.queryAfter("sid-t2", "rid", 4)).thenReturn(Flux.empty());
        when(turnLeaseStore.isHeld("sid-t2")).thenReturn(false);
        when(eventStore.findLatest("sid-t2")).thenReturn(
            new SessionEventStore.EnvelopedEvent(4, "TEXT_BLOCK_DELTA", "{}", "rid"));
        when(runtimeService.findPendingConfirm("sid-t2")).thenReturn(null);

        StepVerifier.create(tailer.tail("sid-t2", "rid", 0))
            .expectNextMatches(sse -> sse.data().contains("partial"))
            .expectNextMatches(sse -> sse.data().contains("interrupted"))
            .verifyComplete();
    }

    @Test
    void tailNeverTouchesLocalSink() {
        // 终止路径不应依赖任何进程内状态：本用例不含 SessionEventBus，
        // 若 tail 实现引用了本地 sink，构造期即失败。
        when(turnLeaseStore.isHeld("sid-t3")).thenReturn(false);
        when(eventStore.queryAfter("sid-t3", null, 0)).thenReturn(Flux.empty());
        when(eventStore.findLatest("sid-t3")).thenReturn(null);

        StepVerifier.create(tailer.tail("sid-t3", null, 0))
            .expectNextMatches(sse -> sse.data().contains("done"))
            .verifyComplete();
    }
```

在文件头部补 import：

```java
import reactor.test.StepVerifier;
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=SessionEventTailerTest`
Expected: 编译失败 —— `cannot find symbol: method tail(java.lang.String,java.lang.String,int)`

- [ ] **Step 3: 实现**

在 `SessionEventTailer` 中追加追赶循环（并补 import：`org.springframework.http.codec.ServerSentEvent`、`reactor.core.publisher.Flux`、`reactor.core.scheduler.Schedulers`）：

```java
    /**
     * 观察者流：从 afterSeq 回放，然后按游标轮询追赶，直到 turn 终止。
     *
     * <p>全程只读 DB，不使用任何进程内状态——这正是跨副本正确性的来源：被订阅的
     * session 的执行可能发生在另一个 Pod 上，它的 sink 在本 Pod 不可达。
     *
     * <p>完成判定见 {@link #probe(String)}：正常结束补 done 帧；
     * 执行副本崩溃/被抢占补 interrupted 帧。
     */
    public Flux<ServerSentEvent<String>> tail(String sessionId, String replyId, int afterSeq) {
        var cursor = new java.util.concurrent.atomic.AtomicInteger(Math.max(afterSeq, -1));

        // afterSeq < 0 表示不回放（与 SessionEventBus.subscribe 的既有语义一致）
        Flux<ServerSentEvent<String>> replay = afterSeq < 0
            ? Flux.empty()
            : eventStore.queryAfter(sessionId, replyId, afterSeq)
                .doOnNext(e -> cursor.set(e.seq()))
                .map(e -> toSSE(e, replyId));

        Flux<ServerSentEvent<String>> live = Flux.<ServerSentEvent<String>>create(sink -> {
            long lastProbeAt = 0;   // 0 → 首轮立即探测，避免对已结束的 turn 空等一轮
            while (!sink.isCancelled()) {
                var page = eventStore.queryAfter(sessionId, replyId, cursor.get())
                    .collectList().block();
                if (page == null) page = java.util.List.of();

                boolean sawTerminal = false;
                for (var e : page) {
                    if (sink.isCancelled()) return;
                    sink.next(toSSE(e, replyId));
                    cursor.set(e.seq());
                    if (TERMINAL_TYPES.contains(e.type())) sawTerminal = true;
                }

                if (sawTerminal) {
                    sink.next(doneSSE());
                    sink.complete();
                    return;
                }

                // 终止探测降频：空闲时 ~2s 一次，避免每轮轮询都查 lease + confirm
                long now = System.currentTimeMillis();
                if (page.isEmpty() && now - lastProbeAt >= PROBE_INTERVAL_MS) {
                    lastProbeAt = now;
                    var probe = probe(sessionId);
                    if (probe.interrupted()) {
                        sink.next(interruptedSSE());
                        sink.complete();
                        return;
                    }
                    if (probe.finished()) {
                        sink.next(doneSSE());
                        sink.complete();
                        return;
                    }
                }

                try {
                    Thread.sleep(pollInterval.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    sink.complete();
                    return;
                }
            }
        }).subscribeOn(Schedulers.boundedElastic());

        return replay.concatWith(live);
    }

    private ServerSentEvent<String> toSSE(SessionEventStore.EnvelopedEvent e, String replyId) {
        // 与 SessionEventBus.toSSE 保持一致的 id（seq）语义，前端可据此记录回放游标
        return ServerSentEvent.<String>builder()
            .data(e.payload())
            .id(String.valueOf(e.seq()))
            .build();
    }

    private static ServerSentEvent<String> doneSSE() {
        return ServerSentEvent.<String>builder().data("{\"type\":\"done\"}").build();
    }

    private static ServerSentEvent<String> interruptedSSE() {
        return ServerSentEvent.<String>builder()
            .data("{\"type\":\"interrupted\",\"reason\":\"turn_interrupted\"}")
            .build();
    }
```

> 说明：`queryAfter` 已在 Task 4 中改为分页且不跨 `sink.next` 持连接，这里用 `collectList().block()` 消费一页是安全的（每页独立短连接）。`block()` 跑在 `boundedElastic` 上，不阻塞事件循环线程。
>
> 注意 `replyId` 的 JSON 注入：`SessionEventBus.toSSE` 会把 replyId 注入 payload。观察者路径按 `replyId` 过滤已在 SQL 层完成（`queryAfter` 的 `reply_id = ?`），因此不需要再注入。若前端依赖该字段，在 Task 9 的验证中确认并在此处补上同样的注入逻辑。

修改 `SessionStreamController.subscribe`：

```java
    /**
     * 订阅 session 的事件流（回放 + 游标追赶）。
     *
     * <p>跨副本安全：全程只读 DB，不依赖本 Pod 是否执行过该 session。
     *
     * <ul>
     *   <li>{@code afterSeq}：回放游标，从 lastEventId 之后开始回放</li>
     *   <li>{@code replyId}：turn 标识，仅回放/订阅指定 turn 的事件</li>
     * </ul>
     */
    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> subscribe(
            @PathVariable String sessionId,
            @RequestParam(required = false) Integer afterSeq,
            @RequestParam(required = false) String replyId) {

        sessionId = io.agentmanager.framework.util.PathSafe.sanitize(sessionId);
        return tailer.tail(sessionId, replyId, afterSeq != null ? afterSeq : 0);
    }
```

删除 `SessionStreamController` 中不再使用的 `replayAndClose` 方法。删除后 `TurnStatus` 与 `eventBus` 字段若变为未使用则一并清理。

在 `SessionEventBus` 中删除以下方法（已逐个核对调用点，删除后无引用）：
- `turnStatus(String)` 与 `TurnStatus` 枚举 —— 调用点仅 `SessionStreamController` 的 `subscribe`/`status`，均已在本任务与 Task 6 中移除
- `replayOnly(String, String, int)` —— 调用点仅 `SessionStreamController.replayAndClose`（本任务删除）
- `currentReplyId(String)` —— 调用点仅 `SessionStreamController.status` 的 `reply_id` 回退分支（Task 6 已移除）
- `getEventStore()` —— **无任何调用点**（javadoc 声称"控制器 status 端点用"，实际 status 端点用的是注入的 `eventStore` 字段）

删除 `TurnStatus` 枚举后，同步删除 `ChatStreamController.java:25` 与 `SessionStreamController.java:15` 的 `import ...SessionEventBus.TurnStatus;`。

`SessionStreamController` 的 `eventBus` 字段在此之后不再被使用，一并删除该字段与构造参数：

```java
    private final TurnLeaseStore turnLeaseStore;
    private final AgentRuntimeService runtimeService;
    private final SessionEventStore eventStore;
    private final SessionEventTailer tailer;

    public SessionStreamController(AgentRuntimeService runtimeService,
                                   TurnLeaseStore turnLeaseStore,
                                   SessionEventStore eventStore,
                                   SessionEventTailer tailer) {
        this.runtimeService = runtimeService;
        this.turnLeaseStore = turnLeaseStore;
        this.eventStore = eventStore;
        this.tailer = tailer;
    }
```

同步修改 Task 6 新建的 `SessionStreamControllerStatusTest`：构造器改为 4 参（去掉 `eventBus`），并删除 `workingWhenLeaseHeldEvenWithoutLocalSink` 中的 `verify(eventBus, never()).turnStatus(anyString());` 一行——该断言在 `eventBus` 依赖被删除后已无意义，状态断言本身保留。

同时删除 `SessionEventBusTest` 中三个针对已删方法的用例（否则编译不过）：
- `turnStatusWorkingWhenSinkExists`
- `turnStatusIdleWhenNoSinkOrEvents`
- `turnStatusCompletedWhenLatestIsAgentEnd`

这三个用例覆盖的状态判定语义已由 `SessionEventTailerTest` 与 `SessionStreamControllerStatusTest` 以 DB 判据重新覆盖。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest='SessionEventTailerTest,SessionStreamControllerStatusTest,SessionEventBusTest'`
Expected: PASS

Run: `mvn test`
Expected: PASS（全量；若其他地方引用了已删除方法，此步会暴露编译错误并需一并清理）

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "fix(agent-framework): subscribe 改走 DB 游标追赶，修复跨副本丢事件与悬挂

对应 durable-sse-multinode-plan §3.4.1。原实现的实时流与完成信号都派生自
本 Pod 的 sink：跨副本订阅时实时事件收不到，且 completionSignal 永不完成
（heartbeat 是无限流，takeUntilOther 不触发），流悬挂至 sink 被回收——
而 evictStaleSinks 当时只在每日 03:00 执行，实际可悬挂近 24 小时。

改为只读 DB 的游标追赶后，跨副本不再丢事件、turn 结束正常关流；多标签页
的 replay→live 接缝一并消失。subscribe 不再调用 ensureSink（消除残留
sink 污染状态判定的问题）。"
```

---

## Task 8: HITL 处关闭流

**对应设计文档：** §3.4.4（决策：关流）

**解决的问题：** 实现与文档不一致——durable-sse-plan §5.4 述"permission_ask 后关闭 Sinks"，但实现只做了 `storeConfirmContext` + `lease.release()`。当前语义是"流意外地一直开着"，代价是 sink 长驻 + 跨副本观察者在整个 HITL 期间白白轮询。

**前端证据（已核对，见设计文档 §3.4.4）：** `asked` 返回值在两个调用点均未使用；确认卡片经 `onAsk` 回调在流中即时渲染；刷新时用 `pendingConfirm` 重建卡片。即关流不会破坏前端。

**Files:**
- Modify: `src/main/java/io/agentmanager/framework/controller/ChatStreamController.java`
- Modify: `src/main/java/io/agentmanager/framework/controller/ConfirmController.java`
- Modify: `src/main/java/io/agentmanager/framework/controller/SessionStreamController.java`（`subscribe` 的两处调用点改为 `beginTurn`）
- Modify: `src/main/java/io/agentmanager/framework/controller/ChatStreamController.java`（`ensureSink` → `beginTurn`）

- [ ] **Step 1: 写失败测试**

在 `src/test/java/io/agentmanager/framework/service/SessionEventBusTest.java` 中已有 `closeSessionFinishesTurnOnStore`。

本任务的行为是控制器层的时序，用一条针对 `SessionEventBus` 的测试表达契约：**HITL 关流必须发生在 emit(permission_ask) 之后**。追加到 `SessionEventBusTest`：

```java
    @Test
    void closeSessionAfterEmitStillDeliversEventToSubscriber() {
        // HITL 时序契约：先 emit(permission_ask) 再 closeSession，
        // 订阅者必须收到该事件，然后才收到 onComplete。
        when(eventStore.append(anyString(), anyString(), anyString(), anyString())).thenReturn(1);

        var flux = eventBus.subscribe("sid-hitl", 0, "rid-h")
            .filter(sse -> sse.data() != null)
            .take(1)
            .timeout(Duration.ofSeconds(3));

        StepVerifier.create(flux)
            .then(() -> {
                eventBus.emitSynthetic("sid-hitl", "rid-h", "permission_ask",
                    "{\"type\":\"permission_ask\"}");
                eventBus.closeSession("sid-hitl");
            })
            .expectNextMatches(sse -> sse.data().contains("permission_ask"))
            .verifyComplete();
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=SessionEventBusTest`
Expected: PASS 或 FAIL —— 该测试本身应通过（`emit` 先于 `closeSession` 时事件已在缓冲中）。**若失败，说明 emit 与 closeSession 的时序契约被破坏，需先修实现再继续。**

- [ ] **Step 3: 实现**

`ChatStreamController.handleEventAndEmit` 中，在 `eventBus.emit(sessionId, event, replyId);`（第 315 行）**之后**追加 HITL 关流：

```java
        eventBus.emit(sessionId, event, replyId);

        // HITL 是 turn 边界：permission_ask 已广播，关闭 sink 让订阅者正常结束。
        // 必须在 emit 之后——否则订阅者收不到 permission_ask（durable-sse-multinode-plan §3.4.4）。
        if (event instanceof RequireUserConfirmEvent) {
            eventBus.closeSession(sessionId);
        }
```

（原有的 HITL 分支 `storeConfirmContext` + `lease.release()` 保持不变，仍在 emit 之前。）

`ConfirmController.handleEventAndEmit` 中做同样处理（第 190 行 emit 之后）：

```java
        eventBus.emit(sessionId, event, replyId);

        // HITL 是 turn 边界：permission_ask 已广播，关闭 sink（同上）
        if (event instanceof io.agentscope.core.event.RequireUserConfirmEvent) {
            eventBus.closeSession(sessionId);
        }
```

把两个控制器的 turn 开始处由 `eventBus.ensureSink(...)` 改为 `eventBus.beginTurn(...)`：

- `ChatStreamController.java:225`：`eventBus.ensureSink(finalSessionId);` → `eventBus.beginTurn(finalSessionId);`
- `ConfirmController.java:136`：`eventBus.ensureSink(finalSessionId);` → `eventBus.beginTurn(finalSessionId);`

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "fix(agent-framework): HITL 处关闭 EventBus 流，落实 turn 边界语义

对应 durable-sse-multinode-plan §3.4.4。durable-sse-plan §5.4 述
permission_ask 后应关闭 Sinks，但实现只做了 storeConfirmContext +
lease.release()，导致流意外地一直开着。已核对前端不依赖流持续打开
（asked 返回值未被使用、确认卡片走 onAsk 回调即时渲染、刷新时用
pendingConfirm 重建卡片）。

同时把 turn 开始处的 ensureSink 改为 beginTurn（播种 seq 计数器）。"
```

---

## Task 9: 跨副本场景集成验证

**对应设计文档：** §5.3

**目的：** 用可控的方式覆盖"执行在 A、订阅在 B"这一核心场景。真实的双 Pod 手工验证成本高，先用单测把语义钉住；`SessionEventTailer` 不依赖任何进程内状态，这条性质本身就是跨副本安全性的充分条件——本任务的测试就是在为这条性质建立回归网。

**Files:**
- Test: `src/test/java/io/agentmanager/framework/service/SessionEventStoreBatchTest.java`（新建）
- Test: `src/test/java/io/agentmanager/framework/service/SessionEventTailerTest.java`（追加）

- [ ] **Step 1: 写不变量 I1 的回归测试**

创建 `src/test/java/io/agentmanager/framework/service/SessionEventStoreBatchTest.java`：

```java
package io.agentmanager.framework.service;

import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 不变量 I1 回归：DB 中 session_event 的 seq 始终是连续前缀。
 *
 * <p>这是游标回放正确性的前提——若 DB 中出现"里程碑已落库但中间 delta 缺失"，
 * 重连客户端回放会读到空洞，或把游标推进到尚未写入的 seq 之后，永久丢失中间的 delta。
 */
@ExtendWith(MockitoExtension.class)
class SessionEventStoreBatchTest {

    @Mock private DataSource dataSource;

    private SessionEventStore store;

    /** 记录每次批量 INSERT 写入的 seq，模拟 DB 中的实际内容 */
    private final List<Integer> writtenSeqs = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        var conn = mock(java.sql.Connection.class);
        var stmt = mock(java.sql.Statement.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeUpdate(anyString())).thenReturn(0);

        var selectPs = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);
        when(conn.prepareStatement(contains("SELECT COALESCE(MAX"))).thenReturn(selectPs);
        when(selectPs.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(0);

        var insertPs = mock(java.sql.PreparedStatement.class);
        when(conn.prepareStatement(startsWith("INSERT INTO session_event"))).thenReturn(insertPs);
        when(insertPs.executeUpdate()).thenReturn(1);
        // 拦截 setInt 的第 2 个参数（seq）——参数顺序为 sid, seq, type, payload, replyId
        doAnswer(inv -> {
            int idx = inv.getArgument(0);
            int value = inv.getArgument(1);
            if (idx % 5 == 2) writtenSeqs.add(value);
            return null;
        }).when(insertPs).setInt(anyInt(), anyInt());

        store = new SessionEventStore(dataSource, 7, 3, 60_000);
    }

    @Test
    void seqRemainsContiguousPrefixWithDeltaBuffering() {
        store.seedSeq("sid-i1");

        // 5 条 delta：批量大小为 3 → 前 3 条触发一次落库，后 2 条留缓冲
        for (int i = 0; i < 5; i++) {
            store.append("sid-i1", "r", "TEXT_BLOCK_DELTA", "{\"delta\":\"x\"}");
        }
        assertEquals(List.of(1, 2, 3), writtenSeqs, "缓冲未满部分不应落库");

        // 里程碑：缓冲（4,5）与本行（6）一并落库 → 前缀保持连续
        store.append("sid-i1", "r", "AGENT_END", "{}");
        assertEquals(List.of(1, 2, 3, 4, 5, 6), writtenSeqs);

        // 断言前缀连续
        for (int i = 0; i < writtenSeqs.size(); i++) {
            assertEquals(i + 1, writtenSeqs.get(i), "seq 在位置 " + i + " 处不连续: " + writtenSeqs);
        }
    }

    @Test
    void finishTurnFlushesTailSoPrefixStaysContiguous() {
        store.seedSeq("sid-i2");
        store.append("sid-i2", "r", "TEXT_BLOCK_DELTA", "{\"delta\":\"a\"}");
        store.append("sid-i2", "r", "TEXT_BLOCK_DELTA", "{\"delta\":\"b\"}");
        assertEquals(List.of(), writtenSeqs, "缓冲未满不应落库");

        store.finishTurn("sid-i2");
        assertEquals(List.of(1, 2), writtenSeqs);
    }
}
```

- [ ] **Step 2: 运行确认通过**

Run: `mvn test -Dtest=SessionEventStoreBatchTest`
Expected: PASS

- [ ] **Step 3: 追加跨副本追赶的回归测试**

在 `SessionEventTailerTest` 追加（覆盖设计文档用例 #8）：

```java
    @Test
    void tailFollowsEventsWrittenByAnotherReplica() {
        // 场景：执行发生在 Pod A，观察者在 Pod B。
        // Pod B 没有该 session 的 sink，只能读 DB。
        when(eventStore.queryAfter("sid-x", "rid-x", 0))
            .thenReturn(Flux.empty())                                   // 回放：Pod A 尚未产出
            .thenReturn(Flux.empty())                                   // 追赶第 1 轮：仍为空
            .thenReturn(Flux.just(new SessionEventStore.EnvelopedEvent(1, "TEXT_BLOCK_DELTA",
                "{\"type\":\"TEXT_BLOCK_DELTA\",\"delta\":\"from-A\"}", "rid-x")));
        when(eventStore.queryAfter("sid-x", "rid-x", 1))
            .thenReturn(Flux.just(new SessionEventStore.EnvelopedEvent(2, "AGENT_END", "{}", "rid-x")));
        // 追赶第 1 轮为空时会立即探测一次：Pod A 仍在执行 → RUNNING → 继续轮询
        when(turnLeaseStore.isHeld("sid-x")).thenReturn(true);

        StepVerifier.create(tailer.tail("sid-x", "rid-x", 0))
            .expectNextMatches(sse -> sse.data().contains("from-A"))
            .expectNextMatches(sse -> sse.data().contains("done"))
            .verifyComplete();
    }

    @Test
    void concurrentViewersEachGetTheirOwnTail() {
        // 多标签页：第二个订阅者不再受 multicast 语义影响——
        // 每个订阅者独立读 DB，各自持有游标。
        when(eventStore.queryAfter(eq("sid-m"), isNull(), anyInt())).thenAnswer(inv -> {
            int cursor = inv.getArgument(2);
            if (cursor < 1) {
                return Flux.just(new SessionEventStore.EnvelopedEvent(1, "TEXT_BLOCK_DELTA",
                    "{\"type\":\"TEXT_BLOCK_DELTA\",\"delta\":\"shared\"}", null));
            }
            if (cursor < 2) {
                return Flux.just(new SessionEventStore.EnvelopedEvent(2, "AGENT_END", "{}", null));
            }
            return Flux.empty();
        });

        var a = tailer.tail("sid-m", null, 0).collectList().block(Duration.ofSeconds(5));
        var b = tailer.tail("sid-m", null, 0).collectList().block(Duration.ofSeconds(5));

        assertNotNull(a);
        assertNotNull(b);
        assertEquals(2, a.size(), "订阅者 A 应收到 delta + done");
        assertEquals(2, b.size(), "订阅者 B 应收到 delta + done");
        assertTrue(a.get(0).data().contains("shared"), "A 收到的首个事件应是 delta");
        assertTrue(b.get(0).data().contains("shared"), "B 收到的首个事件应是 delta");
    }
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn test -Dtest=SessionEventTailerTest`
Expected: PASS

- [ ] **Step 5: 全量回归**

Run: `mvn test`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "test(agent-framework): 补跨副本追赶与 seq 连续前缀的回归测试

对应 durable-sse-multinode-plan §5.3 用例 #8，以及 §3.1 不变量 I1。"
```

---

## 完成后的验收清单

- [ ] `mvn test` 全绿
- [ ] `git log --oneline` 可见 9 个任务对应的提交
- [ ] 单 turn 落库语句数验证：跑一次真实对话，确认 `session_event` 的 INSERT 次数从 ~2100 降到 ~10 量级（可用 `DebugApiController` 的数据库状态端点或 MySQL general log）
- [ ] 跨副本手工验证（需双副本环境）：Pod A `POST /chat` 执行中，向 Pod B 发 `GET /threads/{sid}/subscribe?afterSeq=0`，确认 (a) 实时事件陆续到达，(b) turn 结束时收到 `done` 帧并关流
- [ ] 崩溃验证：执行中 kill 掉 Pod A，确认 60s 后 `/status` 返回 `interrupted`，且 subscribe 流以 `interrupted` 帧关闭
- [ ] **确认无误后**才可将 replicas 调至 >1

---

## 遗留事项（本计划不做，已记录）

| 项 | 原因 |
|---|------|
| 前端重连（设计文档阶段 4） | `frontend/src/` 全量 grep `subscribe`/`EventSource`/`afterSeq`/`lastEventId`/`/status` 零命中，服务端能力目前无消费方。需另立排期，顺序在本次之后 |
| 攒批参数 YAML 化 | 本计划用构造参数携带默认值（`batchSize=200`、`flushIntervalMs=1000`）。默认值由测算得出，先观察线上实际语句数再决定是否需要暴露为配置 |
| 合并文本攒批（设计文档 §3.5） | 仅当 `session_event` 行数或回放体积成为瓶颈时再做；需改游标语义为 inclusive + 前端重建消息 |
| Redis / 粘性路由 | 已否决，重新评估触发条件见设计文档 §2.3 |
| agent 执行的跨 Pod 恢复 | `agent_state` 持久化已具备基础，但需先解决工具调用幂等性/副作用重放 |

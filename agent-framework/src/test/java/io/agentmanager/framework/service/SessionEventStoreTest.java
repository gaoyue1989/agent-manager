package io.agentmanager.framework.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SessionEventStore 单元测试（durable-sse-plan §5.2）。
 *
 * <p>测试要点：
 * <ul>
 *   <li>append 正常写入并返回递增 seq</li>
 *   <li>append 失败返回 -1 但不抛异常（降级语义）</li>
 *   <li>queryAfter 按游标回放增量事件</li>
 *   <li>findLatest / findMaxSeq 正确返回最新状态</li>
 *   <li>deleteBefore 按时间清理过期记录</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SessionEventStoreTest {

    @Mock
    private DataSource dataSource;

    private SessionEventStore store;

    @BeforeEach
    void setUp() {
        // 不真正连 DB，用 spy 测试非 DB 逻辑
        // 注意：initSchema 会在构造器里调用——需要 mock Connection
        try {
            var conn = mock(java.sql.Connection.class);
            var stmt = mock(java.sql.Statement.class);
            when(dataSource.getConnection()).thenReturn(conn);
            when(conn.createStatement()).thenReturn(stmt);
            when(stmt.executeUpdate(anyString())).thenReturn(0);
            store = new SessionEventStore(dataSource, 7);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void retentionDays() {
        assertEquals(7, store.retentionDays());
    }

    @Test
    void abandonTurnDiscardsBufferedRowsWithoutFlushing() throws Exception {
        // 丢租约的收尾**不能**刷缓冲：缓冲里那些行的 seq 是本副本「以为自己还持锁」时
        // 分配的，此刻新 owner 可能已在同一区间分配过 seq —— 写下去正是 C1 要防的静默重复行。
        //
        // 光验证「abandonTurn 自己不 flush」是不够的：源流不会因为我们放手就停下，被丢弃的
        // 行若只是从缓冲里"标记"掉而没删掉，下一个事件触发的 flush 会把它们一起写出去，
        // 丢弃就成了摆设。所以这里一直观察到下一次 flush 真正落库的内容。
        var conn = mock(java.sql.Connection.class);
        var selectPs = mock(java.sql.PreparedStatement.class);
        var insertPs = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(contains("SELECT COALESCE(MAX"))).thenReturn(selectPs);
        when(selectPs.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(0);
        when(conn.prepareStatement(contains("INSERT INTO session_event"))).thenReturn(insertPs);
        when(insertPs.executeUpdate()).thenReturn(1);

        // 记录真正落到 INSERT 语句上的 (sid, seq)
        var written = new ArrayList<String>();
        var sidByIndex = new HashMap<Integer, String>();
        doAnswer(inv -> {
            sidByIndex.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(insertPs).setString(anyInt(), any());
        doAnswer(inv -> {
            int seqIdx = inv.getArgument(0);
            written.add(sidByIndex.get(seqIdx - 1) + ":" + inv.getArgument(1));
            return null;
        }).when(insertPs).setInt(anyInt(), anyInt());

        store.seedSeq("sid-ab");
        store.seedSeq("sid-other");
        store.append("sid-ab", "rid-1", "TEXT_BLOCK_DELTA", "{\"type\":\"TEXT_BLOCK_DELTA\"}");
        store.append("sid-ab", "rid-1", "TEXT_BLOCK_DELTA", "{\"type\":\"TEXT_BLOCK_DELTA\"}");
        // 另一个 session 的行也在缓冲里：A 的收尾**不得**把它顺带刷出去
        // （这正是「abandonTurn 不是 finishTurn」的可观测差别）
        store.append("sid-other", "rid-2", "TEXT_BLOCK_DELTA", "{\"type\":\"TEXT_BLOCK_DELTA\"}");

        store.abandonTurn("sid-ab");

        verify(insertPs, never()).executeUpdate();

        // 丢弃之后本 session 又来了一个里程碑事件（源流不会因为我们放手就停下）→ 触发 flush
        assertEquals(1, store.append("sid-ab", "rid-1", "AGENT_END", "{}"),
            "计数器已释放 → 下一次 append 重新播种（DB 最大值 0 + 1）");
        assertEquals(List.of("sid-ab:1"), written,
            "只有丢弃后新增的那一行该落库；被丢弃的两行不得在后续 flush 里复活，"
                + "邻居 sid-other 的行也不得被顺带刷出");

        // 邻居的缓冲未受影响，仍由它自己的收尾正常刷出
        store.finishTurn("sid-other");
        assertEquals(List.of("sid-ab:1", "sid-other:1"), written,
            "sid-other 的行一直留在自己的缓冲里，未被丢弃、也未提前落库");
    }

    @Test
    void unseededAppendsMustStillAdvanceSeq() throws Exception {
        // 计数器不在时（`beginTurn` 未跑，或已被 `releaseSeq` 释放而流仍在产出事件）会退回
        // `findMaxSeq(sid) + 1`——而**缓冲里尚未落库的行，MAX 是看不见的**。于是同一条流里
        // 后续每一行都拿到同一个 seq，攒够一批就在多值 INSERT 里自我冲突：
        //   Duplicate entry ... for key 'uk_session_seq'
        // 生产上表现为「本批 200 行（seq 5153..5153）已整批丢弃」。
        //
        // 批量落库之前这不成问题：每次 append 立即 INSERT，MAX 看得见上一行，退回路径恒等于
        // 「上一个 +1」。引入缓冲之后，退回路径只对**第一行**成立。
        var conn = mock(java.sql.Connection.class);
        var selectPs = mock(java.sql.PreparedStatement.class);
        var insertPs = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(contains("SELECT COALESCE(MAX"))).thenReturn(selectPs);
        when(selectPs.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(5152);
        when(conn.prepareStatement(startsWith("INSERT INTO session_event"))).thenReturn(insertPs);
        when(insertPs.executeUpdate()).thenReturn(1);

        var written = new ArrayList<String>();
        var sidByIndex = new HashMap<Integer, String>();
        doAnswer(inv -> {
            sidByIndex.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(insertPs).setString(anyInt(), any());
        doAnswer(inv -> {
            int seqIdx = inv.getArgument(0);
            written.add(sidByIndex.get(seqIdx - 1) + ":" + inv.getArgument(1));
            return null;
        }).when(insertPs).setInt(anyInt(), anyInt());

        store.append("sid-u", "r", "TEXT_BLOCK_DELTA", "{}");
        store.append("sid-u", "r", "TEXT_BLOCK_DELTA", "{}");
        store.finishTurn("sid-u");

        assertEquals(List.of("sid-u:5153", "sid-u:5154"), written,
            "每一行都必须拿到递增的 seq：退回路径不能对同一批的所有行返回同一个值");
    }

    @Test
    void appendsAfterReleaseSeqAlsoAdvance() throws Exception {
        // 同一个坑的另一半：计数器被释放之后流还在产出事件（HITL 暂停点 closeSession 之后、
        // 或 AGENT_END 之后的尾部事件）。`emit` 是先 append 再试 sink，所以尾部事件照样进
        // store——此时惰性播种会从 MAX 重新发号，必须接得上前一批。
        //
        // 这条 mock 的关键是 **MAX 必须跟着 INSERT 走**：写进去的行要能被下一次 SELECT MAX
        // 读到。用固定值的桩会造出一个「MAX 永远停在旧值」的假世界，测出来的东西与真实语义无关。
        var conn = mock(java.sql.Connection.class);
        var selectPs = mock(java.sql.PreparedStatement.class);
        var insertPs = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(contains("SELECT COALESCE(MAX"))).thenReturn(selectPs);
        when(selectPs.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);

        var written = new ArrayList<String>();           // 「库里的行」
        var batch = new ArrayList<String>();             // 本条 INSERT 正在攒的行
        var sidByIndex = new HashMap<Integer, String>();
        // 库中已有 9 行历史数据；之后 MAX 随落库的行使劲涨
        when(rs.getInt(1)).thenAnswer(inv -> written.stream()
            .mapToInt(w -> Integer.parseInt(w.substring(w.indexOf(':') + 1)))
            .max().orElse(9));
        when(conn.prepareStatement(startsWith("INSERT INTO session_event"))).thenReturn(insertPs);
        doAnswer(inv -> {
            sidByIndex.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(insertPs).setString(anyInt(), any());
        doAnswer(inv -> {
            int seqIdx = inv.getArgument(0);
            batch.add(sidByIndex.get(seqIdx - 1) + ":" + inv.getArgument(1));
            return null;
        }).when(insertPs).setInt(anyInt(), anyInt());
        doAnswer(inv -> {                                 // 成功才落库，模拟 MySQL 的真实可见性
            written.addAll(batch);
            batch.clear();
            return 1;
        }).when(insertPs).executeUpdate();

        store.seedSeq("sid-r");
        store.append("sid-r", "r", "THINKING_BLOCK_START", "{}");    // 10，里程碑 → 落库
        store.releaseSeq("sid-r");                                    // 计数器没了
        store.append("sid-r", "r", "TEXT_BLOCK_DELTA", "{}");         // 尾部事件：惰性重新播种
        store.append("sid-r", "r", "TEXT_BLOCK_DELTA", "{}");
        store.finishTurn("sid-r");

        assertEquals(List.of("sid-r:10", "sid-r:11", "sid-r:12"), written,
            "计数器释放后的尾部事件同样必须递增，不能撞回已经落库的 seq");
    }

    @Test
    void stragglerAppendDuringFinalFlushIsNotRenumbered() throws Exception {
        // 与上面两条不同的另一条通向「同一 session 内重号」的路，不需要第二个 writer：
        //
        //   finishTurn:  synchronized { 摘除缓冲 }  →  insertBatch(...)  →  releaseSeq(...)
        //                                               ↑ INSERT 往返期间到达的尾部事件
        //
        // 临界区只包住摘除，INSERT 与 releaseSeq 都在锁外。于是尾部事件从**尚未释放**的计数器
        // 拿到 seq（正确，且大于正在落库的那批），落进一个新缓冲；紧接着 releaseSeq 删掉计数器，
        // 下一次 append 惰性播种改从 SELECT MAX 发号——而 MAX 看不见那个刚缓冲、尚未落库的行
        // → 重号 → 下次刷出时整批撞 uk_session_seq。
        var conn = mock(java.sql.Connection.class);
        var selectPs = mock(java.sql.PreparedStatement.class);
        var insertPs = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(contains("SELECT COALESCE(MAX"))).thenReturn(selectPs);
        when(selectPs.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);

        var written = java.util.Collections.synchronizedList(new ArrayList<String>());
        var batch = new ArrayList<String>();
        var sidByIndex = new HashMap<Integer, String>();
        when(rs.getInt(1)).thenAnswer(inv -> {
            synchronized (written) {                       // MAX 只能看到**已落库**的行
                return written.stream()
                    .mapToInt(w -> Integer.parseInt(w.substring(w.indexOf(':') + 1)))
                    .max().orElse(9);
            }
        });
        when(conn.prepareStatement(startsWith("INSERT INTO session_event"))).thenReturn(insertPs);
        doAnswer(inv -> {
            sidByIndex.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(insertPs).setString(anyInt(), any());
        doAnswer(inv -> {
            int seqIdx = inv.getArgument(0);
            batch.add(sidByIndex.get(seqIdx - 1) + ":" + inv.getArgument(1));
            return null;
        }).when(insertPs).setInt(anyInt(), anyInt());

        var firstInsert = new java.util.concurrent.atomic.AtomicBoolean(true);
        var insertStarted = new java.util.concurrent.CountDownLatch(1);
        var unblockInsert = new java.util.concurrent.CountDownLatch(1);
        doAnswer(inv -> {
            if (firstInsert.compareAndSet(true, false)) {
                insertStarted.countDown();                 // 把 INSERT 卡在「已开始、未提交」
                unblockInsert.await(5, java.util.concurrent.TimeUnit.SECONDS);
            }
            synchronized (written) {
                written.addAll(batch);
            }
            batch.clear();
            return 1;
        }).when(insertPs).executeUpdate();

        store.seedSeq("sid-s");
        store.append("sid-s", "r", "TEXT_BLOCK_DELTA", "{}");          // 10，进缓冲

        var finisher = new Thread(() -> store.finishTurn("sid-s"));
        finisher.start();
        assertTrue(insertStarted.await(5, java.util.concurrent.TimeUnit.SECONDS),
            "测试自身的同步失败：finishTurn 没走到 INSERT");

        store.append("sid-s", "r", "TEXT_BLOCK_DELTA", "{}");          // 11：INSERT 期间的尾部事件
        unblockInsert.countDown();
        finisher.join(5000);
        assertFalse(finisher.isAlive(), "测试自身的同步失败：finishTurn 未结束");

        store.append("sid-s", "r", "TEXT_BLOCK_DELTA", "{}");          // 必须接在 11 之后
        store.finishTurn("sid-s");

        assertEquals(List.of("sid-s:10", "sid-s:11", "sid-s:12"), written,
            "尾部事件的 seq 不能被重新发号：它在缓冲里，而 MAX 看不见缓冲");
    }

    @Test
    void appendReturnsSeqOnSuccess() throws Exception {
        // 用里程碑事件（非 delta）——delta 现在只进缓冲、不落库，见 deltasAreBufferedUntilMilestone
        // Mock: SELECT MAX → 5, INSERT ok
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

        int seq = store.append("sid-1", "rid-1", "TOOL_CALL_START", "{\"type\":\"TOOL_CALL_START\"}");
        assertEquals(6, seq);
    }

    @Test
    void appendReturnsMinusOneOnFailure() throws Exception {
        // 用里程碑事件：delta 的落库是延迟的，失败语义只在 flush 时体现
        when(dataSource.getConnection()).thenThrow(new RuntimeException("connection failed"));

        int seq = store.append("sid-1", "rid-1", "TOOL_CALL_START", "{}");
        assertEquals(-1, seq);
    }

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

    @Test
    void queryAfterReturnsFlux() throws Exception {
        var conn = mock(java.sql.Connection.class);
        var ps = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        // 两条结果
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getInt("seq")).thenReturn(3, 4);
        when(rs.getString("event_type")).thenReturn("TEXT_BLOCK_DELTA", "AGENT_END");
        when(rs.getString("payload")).thenReturn("{\"type\":\"TEXT_BLOCK_DELTA\"}", "{\"type\":\"AGENT_END\"}");
        when(rs.getString("reply_id")).thenReturn("rid-1", "rid-1");

        var flux = store.queryAfter("sid-1", "rid-1", 2);
        StepVerifier.create(flux)
            .expectNextMatches(e -> e.seq() == 3 && "TEXT_BLOCK_DELTA".equals(e.type()))
            .expectNextMatches(e -> e.seq() == 4 && "AGENT_END".equals(e.type()))
            .verifyComplete();
    }

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

    @Test
    void queryAfterErrorsWhenPageQueryFails() throws Exception {
        var queryConn = mock(java.sql.Connection.class);
        when(dataSource.getConnection()).thenReturn(queryConn);
        when(queryConn.prepareStatement(anyString()))
            .thenThrow(new java.sql.SQLException("db down"));

        // 回放中途查询失败必须是 error，不能静默 complete——
        // 否则重连的客户端会拿到被截断的回放却以为自己已追平
        StepVerifier.create(store.queryAfter("sid-err", "rid-1", 0))
            .expectError(java.sql.SQLException.class)
            .verify();
    }

    @Test
    void findLatestReturnsNullWhenEmpty() throws Exception {
        var conn = mock(java.sql.Connection.class);
        var ps = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        assertNull(store.findLatest("sid-empty"));
    }

    @Test
    void findMaxSeqReturnsZeroWhenEmpty() throws Exception {
        var conn = mock(java.sql.Connection.class);
        var ps = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(0);

        assertEquals(0, store.findMaxSeq("sid-empty"));
    }

    @Test
    void deleteBeforeReturnsCount() throws Exception {
        var conn = mock(java.sql.Connection.class);
        var ps = mock(java.sql.PreparedStatement.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(5);

        int deleted = store.deleteBefore(Instant.now().minus(Duration.ofDays(7)));
        assertEquals(5, deleted);
    }

    @Test
    void envelopedEventRecord() {
        var e = new SessionEventStore.EnvelopedEvent(1, "TEXT_BLOCK_DELTA", "{\"delta\":\"hello\"}", "rid-1");
        assertEquals(1, e.seq());
        assertEquals("TEXT_BLOCK_DELTA", e.type());
        assertEquals("{\"delta\":\"hello\"}", e.payload());
        assertEquals("rid-1", e.replyId());
    }

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
        // lenient：本用例期望它**不被调用**。保留 stub 是为了让"提前落库"这种实现错误
        // 表现为断言失败，而不是 mock 默认返回 null → NPE 被 insertBatch 吞掉后静默通过。
        lenient().when(conn.prepareStatement(startsWith("INSERT INTO session_event"))).thenReturn(insertPs);

        store.seedSeq("sid-buf");
        store.append("sid-buf", "r", "TEXT_BLOCK_DELTA", "{\"delta\":\"a\"}");
        store.append("sid-buf", "r", "TEXT_BLOCK_DELTA", "{\"delta\":\"b\"}");

        // 纯 delta：连 INSERT 语句都不准备，更不落库
        verify(conn, never()).prepareStatement(startsWith("INSERT INTO session_event"));
        verify(insertPs, never()).executeUpdate();
    }

    @Test
    void allDeltaSuffixTypesAreBuffered() throws Exception {
        // 事件词表由外部依赖 io.agentscope.core.event.AgentEventType 拥有，`*_DELTA` 共 6 种。
        // 原先硬编码白名单只列了 2 种（TEXT/THINKING_BLOCK_DELTA），另外 4 种落进里程碑分支、
        // 每条都立刻刷一次批：线上实测 88,445 条 TOOL_CALL_DELTA 就是 88,445 条 INSERT。
        // 这条用例把 6 种全覆盖，挡住「再退回白名单式判定」这个方向。
        var conn = mock(java.sql.Connection.class);
        var selectPs = mock(java.sql.PreparedStatement.class);
        var insertPs = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(contains("SELECT COALESCE(MAX"))).thenReturn(selectPs);
        when(selectPs.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(0);
        lenient().when(conn.prepareStatement(startsWith("INSERT INTO session_event"))).thenReturn(insertPs);

        store.seedSeq("sid-delta6");
        for (String type : List.of("TEXT_BLOCK_DELTA", "THINKING_BLOCK_DELTA", "DATA_BLOCK_DELTA",
                                   "TOOL_CALL_DELTA", "TOOL_RESULT_TEXT_DELTA",
                                   "TOOL_RESULT_DATA_DELTA")) {
            store.append("sid-delta6", "r", type, "{}");
        }

        verify(conn, never()).prepareStatement(startsWith("INSERT INTO session_event"));
        verify(insertPs, never()).executeUpdate();
    }

    @Test
    void milestoneTypesStillFlushImmediately() throws Exception {
        // 后缀判定的另一侧：非 `*_DELTA` 一律里程碑，必须立即刷出——回放的骨架
        // （turn 起止、块边界、工具调用边界）不能压在缓冲里等攒批。
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

        store.seedSeq("sid-mile");
        for (String type : List.of("AGENT_START", "TEXT_BLOCK_START", "TOOL_CALL_START",
                                   "TOOL_CALL_END", "TEXT_BLOCK_END", "AGENT_END")) {
            store.append("sid-mile", "r", type, "{}");
        }

        // 每个里程碑各自一批（到达时缓冲为空）→ 6 次刷出，而不是全压在最后一起写
        verify(insertPs, times(6)).executeUpdate();
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
        // 3 行数据 → 3 组 (?,?,?,?,?,NOW(3))；每行 6 个 ?，故按 NOW(3) 计数
        long groups = sql.split("NOW\\(3\\)", -1).length - 1;
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

    // ===== uk_session_seq 的幂等迁移（既有库补唯一键）=====

    /** 建表 + 补键路径所需的连接桩（只桩公共部分，各用例按需补） */
    private MigrationProbe migrationProbe() throws Exception {
        var probe = new MigrationProbe();
        when(dataSource.getConnection()).thenReturn(probe.conn);
        when(probe.conn.createStatement()).thenReturn(probe.stmt);
        when(probe.conn.getMetaData()).thenReturn(probe.meta);
        when(probe.meta.getIndexInfo(null, null, "session_event", false, false))
            .thenReturn(probe.indexRs);
        return probe;
    }

    private static final class MigrationProbe {
        final java.sql.Connection conn = mock(java.sql.Connection.class);
        final java.sql.Statement stmt = mock(java.sql.Statement.class);
        final java.sql.DatabaseMetaData meta = mock(java.sql.DatabaseMetaData.class);
        final java.sql.ResultSet indexRs = mock(java.sql.ResultSet.class);
        final java.sql.PreparedStatement dupPs = mock(java.sql.PreparedStatement.class);
        final java.sql.ResultSet dupRs = mock(java.sql.ResultSet.class);
    }

    @Test
    void addsUniqueKeyOnCleanExistingTable() throws Exception {
        var probe = migrationProbe();
        when(probe.indexRs.next()).thenReturn(false);   // 无任何索引 → uk_session_seq 不存在
        when(probe.conn.prepareStatement(contains("HAVING c > 1"))).thenReturn(probe.dupPs);
        when(probe.dupPs.executeQuery()).thenReturn(probe.dupRs);
        when(probe.dupRs.next()).thenReturn(false);     // 无重复行 → 可以干净加键

        new SessionEventStore(dataSource, 7);

        // 加键与删冗余索引必须是**两条独立语句**：合成一条原子 DDL 看着漂亮，但
        // 「ADD 成功 + DROP 因索引不存在而报错」会让整条语句回滚——兜底键就悄悄没了。
        // 故断言各自成句，而不是只断言两个片段都出现过。
        verify(probe.stmt).executeUpdate(argThat(
            (String s) -> s.contains("ADD UNIQUE KEY uk_session_seq (session_id, seq)")
                && !s.contains("DROP INDEX")));
        verify(probe.stmt).executeUpdate(argThat(
            (String s) -> s.contains("DROP INDEX idx_session_seq") && !s.contains("ADD UNIQUE KEY")));
    }

    @Test
    void skipsMigrationWhenUniqueKeyAlreadyPresent() throws Exception {
        // 幂等：键已在就什么都不做。这是重启路径最常走的一条，也是安全的一条。
        var probe = migrationProbe();
        // 结果集里恰好一行。**必须**给有限的行数（而不是 thenReturn(true)）：
        // 后者在「名字匹配」失效时会变成不终止的循环，把变异验证拖成 OOM 而不是一次失败。
        when(probe.indexRs.next()).thenReturn(true, false);
        when(probe.indexRs.getString("INDEX_NAME")).thenReturn("uk_session_seq");
        // 预检桩设成 lenient：正确实现根本不会走到它，但「键已存在」的判断一旦失效，
        // 代码就会跑到这里并发出 ALTER —— 那时本用例要靠 never(ALTER) 响亮地失败
        lenient().when(probe.conn.prepareStatement(contains("HAVING c > 1"))).thenReturn(probe.dupPs);
        lenient().when(probe.dupPs.executeQuery()).thenReturn(probe.dupRs);
        lenient().when(probe.dupRs.next()).thenReturn(false);

        new SessionEventStore(dataSource, 7);

        verify(probe.stmt, never()).executeUpdate(contains("ALTER TABLE session_event"));
        // 键已在，连重复预检都不必跑（预检是给 ALTER 兜底的）
        verify(probe.conn, never()).prepareStatement(contains("HAVING c > 1"));
    }

    @Test
    void loudlySkipsMigrationWhenDuplicatesExist() throws Exception {
        // 既定策略「响亮跳过」：不自动删数据、不 abort 启动，只把涉事 session 列出来交人工。
        // 已实测：有重复行时 ADD UNIQUE KEY 必然报 1062，所以这条路必须走在 ALTER 前面。
        var probe = migrationProbe();
        when(probe.indexRs.next()).thenReturn(false);
        when(probe.conn.prepareStatement(contains("HAVING c > 1"))).thenReturn(probe.dupPs);
        when(probe.dupPs.executeQuery()).thenReturn(probe.dupRs);
        when(probe.dupRs.next()).thenReturn(true, false);   // 恰好一组重复
        when(probe.dupRs.getString("session_id")).thenReturn("dup-sid");
        when(probe.dupRs.getInt("seq")).thenReturn(7);
        when(probe.dupRs.getInt("c")).thenReturn(2);

        try (var logs = new LogCapture()) {
            new SessionEventStore(dataSource, 7);

            verify(probe.stmt, never()).executeUpdate(contains("ALTER TABLE session_event"));
            assertTrue(logs.messages().stream()
                    .anyMatch(m -> m.contains("不创建") && m.contains("HAVING c > 1")),
                "应 ERROR 记下「本次不建键」并附排查 SQL，实际日志: " + logs.messages());
            assertTrue(logs.messages().stream().anyMatch(m ->
                    m.contains("dup-sid") && m.contains("seq=7") && m.contains("行数=2")),
                "应逐条列出涉事 session，实际日志: " + logs.messages());
        }
    }

    @Test
    void duplicateKeyCollisionIsReportedAsSecondWriter() throws Exception {
        // uk_session_seq 撞上 = 同一 session 出现了第二个 writer（I2 被违反）。
        // 它与「DB 抖动」的处置相同（整批丢弃、返回 -1），差别全在日志——但那条日志
        // 正是这个兜底存在的意义，所以要钉住它确实被记下来了。
        var conn = mock(java.sql.Connection.class);
        var selectPs = mock(java.sql.PreparedStatement.class);
        var insertPs = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(contains("SELECT COALESCE(MAX"))).thenReturn(selectPs);
        when(selectPs.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(3);
        when(conn.prepareStatement(startsWith("INSERT INTO session_event"))).thenReturn(insertPs);
        when(insertPs.executeUpdate()).thenThrow(
            new java.sql.SQLIntegrityConstraintViolationException(
                "Duplicate entry 'sid-dup-4' for key 'session_event.uk_session_seq'"));

        int seq;
        List<String> logs;
        try (var captured = new LogCapture()) {
            seq = store.append("sid-dup", "r", "TOOL_CALL_START", "{}");
            logs = captured.messages();
        }

        assertEquals(-1, seq, "整批丢弃，降级语义不变");
        assertTrue(logs.stream().anyMatch(m -> m.contains("第二个 writer") && m.contains("sid-dup")),
            "唯一键冲突必须被记为「第二个 writer」，而不是含糊的 batch insert failed，实际日志: "
                + logs);
    }

    /** 把 SessionEventStore 的日志挂到 ListAppender 上（用完 close 摘除） */
    private static final class LogCapture implements AutoCloseable {
        private final ch.qos.logback.classic.Logger logger;
        private final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>
            appender = new ch.qos.logback.core.read.ListAppender<>();

        LogCapture() {
            logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(SessionEventStore.class);
            appender.start();
            logger.addAppender(appender);
        }

        List<String> messages() {
            return appender.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}

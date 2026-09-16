package io.agentmanager.framework.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import io.lettuce.core.RedisConnectionException;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SessionEventStore 单元测试（durable-sse-plan §5.2）。
 *
 * <p>存储引擎从 MySQL 换成 Redis Streams 之后，真正与存储对话的部分全在
 * {@link RedisEventLog}（由 {@code RedisEventLogIT} 用真 Redis 覆盖），本类只覆盖
 * {@link SessionEventStore} 自己承担的**语义**：seq 分配、按 session 分区的攒批、
 * 游标分页、replyId 过滤、宽容/严格读。故这里 mock 的是 {@link RedisEventLog}。
 *
 * <p>测试要点：
 * <ul>
 *   <li>append 正常写入并返回递增 seq</li>
 *   <li>append 失败返回 -1 但不抛异常（降级语义）</li>
 *   <li>seq 计数器的播种与「绝不发重号」（线上真实事故的回归）</li>
 *   <li>queryAfter 按游标回放增量事件、replyId 索引命中与索引缺失两条路径</li>
 *   <li>findLatest / findMaxSeq / findLatestStrict 的正确返回与失败处置</li>
 *   <li>deleteSession 连本进程的缓冲与计数器一并清掉</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SessionEventStoreTest {

    private RedisEventLog eventLog;

    private SessionEventStore store;

    @BeforeEach
    void setUp() {
        eventLog = mock(RedisEventLog.class);
        store = new SessionEventStore(eventLog, 7);
    }

    /** 把每次 {@code appendBatch} 的内容按「sid:seq」展平记录下来，模拟流中实际存在的事件 */
    private List<String> recordFlushed() {
        var written = new ArrayList<String>();
        when(eventLog.appendBatch(anyString(), anyList(), anyInt())).thenAnswer(inv -> {
            String sid = inv.getArgument(0);
            List<RedisEventLog.Entry> entries = inv.getArgument(1);
            for (var e : entries) {
                written.add(sid + ":" + e.seq());
            }
            return entries.size();
        });
        return written;
    }

    @Test
    void retentionDays() {
        assertEquals(7, store.retentionDays());
    }

    @Test
    void ttlSecondsEqualsRetentionDaysTimes86400() {
        // 留存从「03:00 定时 DELETE」换成 Redis key TTL，但**天数语义不变**：
        // 写入时下发的 TTL 必须就是留存天数换算出来的秒数
        assertEquals(7 * 86_400L, store.ttlSeconds());
        assertEquals(3 * 86_400L, new SessionEventStore(eventLog, 3).ttlSeconds(),
            "必须真的按天数换算，而不是写死一个值");
    }

    @Test
    void abandonTurnDiscardsBufferedRowsWithoutFlushing() {
        // 丢租约的收尾**不能**刷缓冲：缓冲里那些行的 seq 是本副本「以为自己还持锁」时
        // 分配的，此刻新 owner 可能已在同一区间分配过 seq —— 写下去正是 C1 要防的静默重复行。
        //
        // 光验证「abandonTurn 自己不 flush」是不够的：源流不会因为我们放手就停下，被丢弃的
        // 行若只是从缓冲里"标记"掉而没删掉，下一个事件触发的 flush 会把它们一起写出去，
        // 丢弃就成了摆设。所以这里一直观察到下一次 flush 真正落库的内容。
        when(eventLog.tailSeq(anyString())).thenReturn(0);
        var written = recordFlushed();

        store.seedSeq("sid-ab");
        store.seedSeq("sid-other");
        store.append("sid-ab", "rid-1", "TEXT_BLOCK_DELTA", "{\"type\":\"TEXT_BLOCK_DELTA\"}");
        store.append("sid-ab", "rid-1", "TEXT_BLOCK_DELTA", "{\"type\":\"TEXT_BLOCK_DELTA\"}");
        // 另一个 session 的行也在缓冲里：A 的收尾**不得**把它顺带刷出去
        // （这正是「abandonTurn 不是 finishTurn」的可观测差别）
        store.append("sid-other", "rid-2", "TEXT_BLOCK_DELTA", "{\"type\":\"TEXT_BLOCK_DELTA\"}");

        store.abandonTurn("sid-ab");

        verify(eventLog, never()).appendBatch(anyString(), anyList(), anyInt());

        // 丢弃之后本 session 又来了一个里程碑事件（源流不会因为我们放手就停下）→ 触发 flush
        assertEquals(1, store.append("sid-ab", "rid-1", "AGENT_END", "{}"),
            "计数器已释放 → 下一次 append 重新播种（流顶端 0 + 1）");
        assertEquals(List.of("sid-ab:1"), written,
            "只有丢弃后新增的那一行该落库；被丢弃的两行不得在后续 flush 里复活，"
                + "邻居 sid-other 的行也不得被顺带刷出");

        // 邻居的缓冲未受影响，仍由它自己的收尾正常刷出
        store.finishTurn("sid-other");
        assertEquals(List.of("sid-ab:1", "sid-other:1"), written,
            "sid-other 的行一直留在自己的缓冲里，未被丢弃、也未提前落库");
    }

    @Test
    void unseededAppendsMustStillAdvanceSeq() {
        // 计数器不在时（`seedSeq` 未跑，或已被 `releaseSeq` 释放而流仍在产出事件）会惰性播种——
        // 而**缓冲里尚未刷出的行，流顶端是看不见的**。于是同一条流里后续每一行都拿到同一个 seq，
        // 攒够一批后整批撞在同一个 ID 上被 Redis 拒绝：
        //   ERR The ID specified in XADD is equal or smaller than the target stream top item
        // 生产上表现为「本批 200 行（seq 5153..5153）已整批丢弃」。
        //
        // 批量落库之前这不成问题：每次 append 立即 INSERT，流顶端看得见上一行，播种恒等于
        // 「上一个 +1」。引入缓冲之后，只看流顶端的播种只对**第一行**成立。
        // 现在的下界取 max(流顶端, 本 session 缓冲中的最大 seq)（见 seedFloor），
        // 所以「缓冲里的行已经被发过号」这件事不会再被无视。
        when(eventLog.tailSeq("sid-u")).thenReturn(5152);
        var written = recordFlushed();

        store.append("sid-u", "r", "TEXT_BLOCK_DELTA", "{}");     // 5153，进缓冲
        store.append("sid-u", "r", "TEXT_BLOCK_DELTA", "{}");     // 5154，进缓冲
        store.releaseSeq("sid-u");                                // 计数器被丢掉，而缓冲还在
        store.append("sid-u", "r", "TEXT_BLOCK_DELTA", "{}");     // 必须接在 5154 之后
        store.finishTurn("sid-u");

        assertEquals(List.of("sid-u:5153", "sid-u:5154", "sid-u:5155"), written,
            "每一行都必须拿到递增的 seq：惰性播种不能对同一批的所有行返回同一个值，"
                + "也不能把已经发给调用方的 5153/5154 重发一遍");
    }

    @Test
    void appendsAfterReleaseSeqAlsoAdvance() {
        // 同一个坑的另一半：计数器被释放之后流还在产出事件（HITL 暂停点 closeSession 之后、
        // 或 AGENT_END 之后的尾部事件）。`emit` 是先 append 再试 sink，所以尾部事件照样进
        // store——此时惰性播种会从流顶端重新发号，必须接得上前一批。
        //
        // 这条 mock 的关键是 **流顶端必须跟着刷出走**：写进去的行要能被下一次 tailSeq 读到。
        // 用固定值的桩会造出一个「顶端永远停在旧值」的假世界，测出来的东西与真实语义无关
        // （那样的世界里重新播种会直接撞回已刷出的 seq，而这条用例正该失败）。
        var written = recordFlushed();           // 「流里的行」
        when(eventLog.tailSeq("sid-r")).thenAnswer(inv -> written.stream()
            .mapToInt(w -> Integer.parseInt(w.substring(w.indexOf(':') + 1)))
            .max().orElse(9));                   // 流中已有 9 行历史数据

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
        //   finishTurn:  synchronized { 摘除缓冲 }  →  appendBatch(...)  →  releaseSeqIfIdle(...)
        //                                                    ↑ 刷出往返期间到达的尾部事件
        //
        // 临界区只包住摘除，刷出与释放都在锁外。于是尾部事件从**尚未释放**的计数器拿到 seq
        // （正确，且大于正在刷出的那批），落进一个新建的缓冲；紧接着计数器被释放，下一次 append
        // 惰性播种改从流顶端发号——而流顶端看不见那个刚缓冲、尚未落库的行 → 重号 → 下次刷出
        // 时整批撞在同一个 ID 上被拒。
        var written = Collections.synchronizedList(new ArrayList<String>());
        when(eventLog.tailSeq("sid-s")).thenAnswer(inv -> {
            synchronized (written) {                  // 流顶端只能看到**已刷出**的行
                return written.stream()
                    .mapToInt(w -> Integer.parseInt(w.substring(w.indexOf(':') + 1)))
                    .max().orElse(9);
            }
        });

        var firstFlush = new AtomicBoolean(true);
        var flushStarted = new CountDownLatch(1);
        var unblockFlush = new CountDownLatch(1);
        when(eventLog.appendBatch(eq("sid-s"), anyList(), anyInt())).thenAnswer(inv -> {
            List<RedisEventLog.Entry> entries = inv.getArgument(1);
            if (firstFlush.compareAndSet(true, false)) {
                flushStarted.countDown();             // 把刷出卡在「已开始、未确认」
                unblockFlush.await(5, TimeUnit.SECONDS);
            }
            synchronized (written) {
                for (var e : entries) {
                    written.add("sid-s:" + e.seq());
                }
            }
            return entries.size();
        });

        store.seedSeq("sid-s");
        store.append("sid-s", "r", "TEXT_BLOCK_DELTA", "{}");          // 10，进缓冲

        var finisher = new Thread(() -> store.finishTurn("sid-s"));
        finisher.start();
        assertTrue(flushStarted.await(5, TimeUnit.SECONDS),
            "测试自身的同步失败：finishTurn 没走到刷出");

        store.append("sid-s", "r", "TEXT_BLOCK_DELTA", "{}");          // 11：刷出期间的尾部事件
        unblockFlush.countDown();
        finisher.join(5000);
        assertFalse(finisher.isAlive(), "测试自身的同步失败：finishTurn 未结束");

        // 与 MySQL 版的差别：releaseSeqIfIdle 现在只在缓冲为空时才释放，所以上面那次收尾**没有**
        // 释放计数器（11 已经落在新缓冲里）。但释放路径本身仍在（租约交接、deleteSession、
        // 空缓冲收尾），且必须同样安全——这里显式释放，把「计数器没了、而 11 还躺在缓冲里」
        // 这一刻精确地造出来。
        store.releaseSeq("sid-s");
        store.append("sid-s", "r", "TEXT_BLOCK_DELTA", "{}");          // 必须接在 11 之后
        store.finishTurn("sid-s");

        assertEquals(List.of("sid-s:10", "sid-s:11", "sid-s:12"), written,
            "尾部事件的 seq 不能被重新发号：它在缓冲里，而流顶端看不见缓冲");
    }

    @Test
    void appendReturnsSeqOnSuccess() {
        // 用里程碑事件（非 delta）——delta 只进缓冲、不落库，见 deltasAreBufferedUntilMilestone
        // Mock: 流顶端 5, appendBatch 成功
        when(eventLog.tailSeq("sid-1")).thenReturn(5);
        when(eventLog.appendBatch(eq("sid-1"), anyList(), anyInt())).thenReturn(1);

        int seq = store.append("sid-1", "rid-1", "TOOL_CALL_START", "{\"type\":\"TOOL_CALL_START\"}");
        assertEquals(6, seq);
    }

    @Test
    void appendReturnsMinusOneOnFailure() {
        // -1 当且仅当 appendBatch 返回 < 0。用里程碑事件：delta 的刷出是延迟的，
        // 失败语义只在 flush 时体现
        when(eventLog.appendBatch(eq("sid-1"), anyList(), anyInt())).thenReturn(-1);

        int seq = store.append("sid-1", "rid-1", "TOOL_CALL_START", "{}");
        assertEquals(-1, seq);
    }

    @Test
    void seedSeqThenAppendDoesNotQueryMaxAgain() {
        // 名字沿用（回归来源），性质换成新实现下的表述：播种之后，**每次 append 都不再重读流顶端**。
        // 这条不能退化成「每次 append 读一次 tailSeq + 1」——缓冲里尚未刷出的行对 tailSeq 是
        // 不可见的，那样同一个未播种 session 的后续每一行都会拿到同一个 seq。
        when(eventLog.tailSeq("sid-counter")).thenReturn(5);
        when(eventLog.appendBatch(eq("sid-counter"), anyList(), anyInt())).thenReturn(1);

        store.seedSeq("sid-counter");
        assertEquals(6, store.append("sid-counter", "rid-1", "TOOL_CALL_START", "{}"));
        assertEquals(7, store.append("sid-counter", "rid-1", "TOOL_CALL_START", "{}"));

        // 流顶端只应在 seedSeq 时读一次
        verify(eventLog, times(1)).tailSeq(anyString());
    }

    @Test
    void seedSeqIsIdempotent() {
        when(eventLog.tailSeq("sid-idem")).thenReturn(5);
        when(eventLog.appendBatch(eq("sid-idem"), anyList(), anyInt())).thenReturn(1);

        store.seedSeq("sid-idem");
        store.append("sid-idem", "rid-1", "TOOL_CALL_START", "{}");   // seq=6
        store.seedSeq("sid-idem");                                     // 重复播种不得回退
        assertEquals(7, store.append("sid-idem", "rid-1", "TOOL_CALL_START", "{}"));

        // 与原实现的差别（原断言是「SELECT MAX 只执行一次」）：现在每次 seedSeq 都会读一次
        // 流顶端（XREVRANGE COUNT 1，O(1)），这是设计使然——每 turn 开始时都要从流续起。
        // 不可退让的是「只抬不降」：计数器已经在 6，播种回 5 会让上面的 append 重发 6。
        // 频率仍是「每次 seedSeq 一次」而不是「每次 append 一次」：2 次 seedSeq = 2 次 tailSeq。
        verify(eventLog, times(2)).tailSeq(anyString());
    }

    @Test
    void appendSeedsFromStreamTailWhenNotSeeded() {
        // 未播种：惰性播种的来源由 SELECT COALESCE(MAX(seq)) 换成流顶端 tailSeq()
        when(eventLog.tailSeq("sid-unseeded")).thenReturn(41);
        when(eventLog.appendBatch(eq("sid-unseeded"), anyList(), anyInt())).thenReturn(1);

        // 未播种：退回流顶端查询，保持既有语义
        assertEquals(42, store.append("sid-unseeded", "rid-1", "TOOL_CALL_START", "{}"));
    }

    // ===== 回放：游标分页与 replyId 索引 =====

    @Test
    void queryAfterReturnsFlux() {
        when(eventLog.replies("sid-1"))
            .thenReturn(List.of(new RedisEventLog.ReplyBoundary("rid-1", 1)));
        // 只有一个 reply（没有「下一个 reply 的首个 seq」）→ 上界取 Integer.MAX_VALUE
        when(eventLog.range("sid-1", 3, Integer.MAX_VALUE, SessionEventStore.queryPageSize()))
            .thenReturn(List.of(
                new RedisEventLog.Event(3, "TEXT_BLOCK_DELTA",
                    "{\"type\":\"TEXT_BLOCK_DELTA\"}", "rid-1"),
                new RedisEventLog.Event(4, "AGENT_END",
                    "{\"type\":\"AGENT_END\"}", "rid-1")));

        var flux = store.queryAfter("sid-1", "rid-1", 2);
        StepVerifier.create(flux)
            .expectNextMatches(e -> e.seq() == 3 && "TEXT_BLOCK_DELTA".equals(e.type()))
            .expectNextMatches(e -> e.seq() == 4 && "AGENT_END".equals(e.type()))
            .verifyComplete();
    }

    @Test
    void queryAfterWithKnownReplyIssuesOneBoundedRangeNotAFullScan() {
        // 已知 replyId 必须走 ZSET 索引：一次**有界**的 range（本 reply 的首个 seq
        // .. 下一个 reply 的首个 seq - 1），而不是把整条流扫一遍再过滤。
        when(eventLog.replies("sid-r"))
            .thenReturn(List.of(
                new RedisEventLog.ReplyBoundary("rid-a", 1),
                new RedisEventLog.ReplyBoundary("rid-b", 10)));
        when(eventLog.range("sid-r", 1, 9, SessionEventStore.queryPageSize()))
            .thenReturn(List.of(
                new RedisEventLog.Event(1, "AGENT_START", "{}", "rid-a"),
                new RedisEventLog.Event(2, "TEXT_BLOCK_DELTA", "{}", "rid-a"),
                new RedisEventLog.Event(3, "AGENT_END", "{}", "rid-a")));

        StepVerifier.create(store.queryAfter("sid-r", "rid-a", 0))
            .expectNextCount(3)
            .verifyComplete();

        // 上界必须是**下一个 reply 的首个 seq - 1**：宽一格会把下一个 turn 的事件混进来，
        // 紧一格会静默丢掉本 turn 的最后一条
        verify(eventLog, times(1)).range("sid-r", 1, 9, SessionEventStore.queryPageSize());
        // 而且总共只有这一次 range：不是逐条查询，也不是「先扫全流再在内存里过滤」
        verify(eventLog, times(1)).range(anyString(), anyInt(), any(), anyInt());
    }

    @Test
    void queryAfterWithUnknownReplyAndFreshCursorReturnsEmptyWithoutScanning() {
        // 热路径：每次 POST /threads/chat 都会用一个**刚生成、还没写过任何事件**的 replyId 订阅。
        // 索引里没有它 + afterSeq <= 0 ⇒ 空是正解，而且必须是 O(turns)（只读一次索引），
        // 不能退化成扫全流。
        when(eventLog.replies("sid-new"))
            .thenReturn(List.of(new RedisEventLog.ReplyBoundary("rid-old", 1)));

        StepVerifier.create(store.queryAfter("sid-new", "rid-fresh", 0))
            .verifyComplete();

        verify(eventLog, times(1)).replies("sid-new");
        verify(eventLog, never()).range(anyString(), anyInt(), any(), anyInt());
    }

    @Test
    void queryAfterWithUnknownReplyAndAdvancedCursorFallsBackToScan() {
        // 客户端正在续传一个**已经开始过**的 reply，索引里却没有它——只能是索引丢了。
        // 这时返回空是**静默内容丢失**：客户端会以为自己已追平。宁可慢，也要退化成全流扫描。
        when(eventLog.replies("sid-lost"))
            .thenReturn(List.of(new RedisEventLog.ReplyBoundary("rid-other", 1)));
        when(eventLog.range("sid-lost", 6, null, SessionEventStore.queryPageSize()))
            .thenReturn(List.of(
                new RedisEventLog.Event(6, "TEXT_BLOCK_DELTA", "{}", "rid-other"),
                new RedisEventLog.Event(7, "TEXT_BLOCK_DELTA", "{}", "rid-lost"),
                new RedisEventLog.Event(8, "AGENT_END", "{}", "rid-lost")));

        StepVerifier.create(store.queryAfter("sid-lost", "rid-lost", 5))
            .expectNextMatches(e -> e.seq() == 7 && "rid-lost".equals(e.replyId()))
            .expectNextMatches(e -> e.seq() == 8 && "rid-lost".equals(e.replyId()))
            .verifyComplete();

        verify(eventLog, atLeastOnce())
            .range("sid-lost", 6, null, SessionEventStore.queryPageSize());
    }

    @Test
    void queryAfterReleasesConnectionBeforeEmitting() {
        // JDBC 的 ResultSet/Statement/Connection 已经不在了，这里重钉**底层**性质：
        // 一页在**任何事件被发出之前**就已 materialize 成 List，所以一页 N 条事件只对应
        // 一次 range——慢客户端反压或消费中途取消，都不会把一次查询钉在推送期间。
        when(eventLog.replies("sid-conn"))
            .thenReturn(List.of(new RedisEventLog.ReplyBoundary("rid-1", 1)));
        var materialized = new AtomicBoolean(false);
        // 本 reply 是索引里的最后一个 → 上界 Integer.MAX_VALUE（等价于「不限」）
        when(eventLog.range(anyString(), anyInt(), eq(Integer.MAX_VALUE), anyInt()))
            .thenAnswer(inv -> {
                materialized.set(true);
                return List.of(
                    new RedisEventLog.Event(3, "TEXT_BLOCK_DELTA", "{}", "rid-1"),
                    new RedisEventLog.Event(4, "TEXT_BLOCK_DELTA", "{}", "rid-1"),
                    new RedisEventLog.Event(5, "TEXT_BLOCK_DELTA", "{}", "rid-1"));
            });

        var flux = store.queryAfter("sid-conn", "rid-1", 2);
        StepVerifier.create(flux.doOnNext(e -> assertTrue(materialized.get(),
                "页必须在第一个事件被发出之前就 materialize 完：发出 seq=" + e.seq() + " 时还没查完")))
            .expectNextCount(3)
            .verifyComplete();

        // 3 条事件只对应 1 次 range：逐条边读边发会让慢客户端的推送时长等于查询时长
        verify(eventLog, times(1)).range(anyString(), anyInt(), eq(Integer.MAX_VALUE), anyInt());
    }

    @Test
    void queryAfterPagesThroughMultipleBatches() {
        when(eventLog.replies("sid-page"))
            .thenReturn(List.of(new RedisEventLog.ReplyBoundary("rid-1", 1)));

        int pageSize = SessionEventStore.queryPageSize();
        var firstPage = new ArrayList<RedisEventLog.Event>(pageSize);
        for (int i = 1; i <= pageSize; i++) {
            firstPage.add(new RedisEventLog.Event(i, "TEXT_BLOCK_DELTA", "{}", "rid-1"));
        }
        var froms = new ArrayList<Integer>();
        when(eventLog.range(anyString(), anyInt(), eq(Integer.MAX_VALUE), anyInt())).thenAnswer(inv -> {
            froms.add(inv.getArgument(1));
            // 第 1 页返回满页（pageSize 行）→ 触发第 2 次查询；第 2 页返回 0 行 → 结束
            return froms.size() == 1 ? firstPage : List.of();
        });

        StepVerifier.create(store.queryAfter("sid-page", "rid-1", 0))
            .expectNextCount(pageSize)
            .verifyComplete();

        // 满页后应再发起一次查询（共 2 次），且下一页必须正好从上一页最后一条之后开始：
        // 重叠会重复推送，跳跃则**静默丢事件**
        assertEquals(List.of(1, pageSize + 1), froms,
            "分页游标必须严格接在上一页之后");
    }

    @Test
    void queryAfterErrorsWhenPageQueryFails() {
        when(eventLog.replies("sid-err"))
            .thenReturn(List.of(new RedisEventLog.ReplyBoundary("rid-1", 1)));
        when(eventLog.range(anyString(), anyInt(), eq(Integer.MAX_VALUE), anyInt()))
            .thenThrow(new RedisConnectionException("redis down"));

        // 回放中途查询失败必须是 error，不能静默 complete——
        // 否则重连的客户端会拿到被截断的回放却以为自己已追平
        StepVerifier.create(store.queryAfter("sid-err", "rid-1", 0))
            .expectError(RedisConnectionException.class)
            .verify();
    }

    @Test
    void negativeAfterSeqIsClampedSoRangeIdIsNeverNegative() {
        // XRANGE key -4-0 + 是 Redis 的硬错误（"Invalid stream ID"），而 afterSeq = -5 直接算
        // afterSeq + 1 就是 -4。必须钳到 0（合法最小 ID，等价于「从头开始」）。
        var froms = new ArrayList<Integer>();
        when(eventLog.range(anyString(), anyInt(), isNull(), anyInt())).thenAnswer(inv -> {
            froms.add(inv.getArgument(1));
            return List.of();
        });

        StepVerifier.create(store.queryAfter("sid-neg", null, -5))
            .verifyComplete();

        assertEquals(1, froms.size());
        assertTrue(froms.get(0) >= 0, "fromSeq 不得为负，实际 " + froms.get(0));
        assertEquals(0, froms.get(0), "-5 应被钳到 0（从头开始）");
    }

    @Test
    void findLatestReturnsNullWhenEmpty() {
        // 无事件：latest 返回 null → findLatest 也返回 null（与「读不到」区分开，见下一条）
        assertNull(store.findLatest("sid-empty"));
    }

    @Test
    void findLatestStrictThrowsWhenStoreUnavailable() {
        // /status 必须能把「没有事件」与「读不到」分开：存储不可用时若返回 null，
        // 调用方会给出 latest_event_seq=0 的**成功**响应，前端据此把游标重置为 0 ——
        // Redis 一恢复就是整场会话重放。所以严格版必须抛。
        when(eventLog.latest("sid-down")).thenThrow(new RedisConnectionException("redis down"));

        assertThrows(RedisConnectionException.class, () -> store.findLatestStrict("sid-down"));

        // 宽容版在同样情形下返回 null（轮询路径静默跳过这一轮，由游标在下一轮补上）
        assertNull(store.findLatest("sid-down"));
    }

    @Test
    void findMaxSeqReturnsZeroWhenEmpty() {
        when(eventLog.tailSeq("sid-empty")).thenReturn(0);
        assertEquals(0, store.findMaxSeq("sid-empty"));
    }

    @Test
    void deleteSessionClearsKeysBufferAndCounter() {
        when(eventLog.tailSeq("sid-del")).thenReturn(0);
        when(eventLog.deleteSession("sid-del")).thenReturn(2);

        store.seedSeq("sid-del");
        assertEquals(1, store.append("sid-del", "r", "TEXT_BLOCK_DELTA", "{}"), "delta 先进缓冲");

        assertEquals(2, store.deleteSession("sid-del"),
            "返回 Redis 实际删掉的 key 数（events + replies）");

        // 缓冲必须一并清掉：级联删除之后再有任何写入，都是把已删会话的数据又写回流里
        store.finishTurn("sid-del");
        verify(eventLog, never()).appendBatch(anyString(), anyList(), anyInt());

        // 计数器同样清掉：下一次 append 重新从流顶端播种（0 + 1），而不是接着 2
        assertEquals(1, store.append("sid-del", "r", "AGENT_END", "{}"),
            "计数器已清 → 新 turn 重新播种");
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
    void deltasAreBufferedUntilMilestone() {
        when(eventLog.tailSeq("sid-buf")).thenReturn(0);
        // lenient：本用例期望它**不被调用**。保留 stub 是为了让"提前刷出"这种实现错误
        // 表现为下面的 never() 断言失败，而不是走 mock 的默认返回路径后静默通过。
        lenient().when(eventLog.appendBatch(anyString(), anyList(), anyInt()))
            .thenAnswer(inv -> ((List<?>) inv.getArgument(1)).size());

        store.seedSeq("sid-buf");
        assertEquals(1, store.append("sid-buf", "r", "TEXT_BLOCK_DELTA", "{\"delta\":\"a\"}"));
        assertEquals(2, store.append("sid-buf", "r", "TEXT_BLOCK_DELTA", "{\"delta\":\"b\"}"));

        // 纯 delta：一次都不刷出
        verify(eventLog, never()).appendBatch(anyString(), anyList(), anyInt());
    }

    @Test
    void allDeltaSuffixTypesAreBuffered() {
        // 事件词表由外部依赖 io.agentscope.core.event.AgentEventType 拥有，`*_DELTA` 共 6 种。
        // 原先硬编码白名单只列了 2 种（TEXT/THINKING_BLOCK_DELTA），另外 4 种落进里程碑分支、
        // 每条都立刻刷一次批：线上实测 88,445 条 TOOL_CALL_DELTA 就是 88,445 次刷出。
        // 这条用例把 6 种全覆盖，挡住「再退回白名单式判定」这个方向。
        when(eventLog.tailSeq("sid-delta6")).thenReturn(0);
        lenient().when(eventLog.appendBatch(anyString(), anyList(), anyInt()))
            .thenAnswer(inv -> ((List<?>) inv.getArgument(1)).size());

        store.seedSeq("sid-delta6");
        var seqs = new ArrayList<Integer>();
        for (String type : List.of("TEXT_BLOCK_DELTA", "THINKING_BLOCK_DELTA", "DATA_BLOCK_DELTA",
                                   "TOOL_CALL_DELTA", "TOOL_RESULT_TEXT_DELTA",
                                   "TOOL_RESULT_DATA_DELTA")) {
            seqs.add(store.append("sid-delta6", "r", type, "{}"));
        }

        verify(eventLog, never()).appendBatch(anyString(), anyList(), anyInt());
        assertEquals(List.of(1, 2, 3, 4, 5, 6), seqs,
            "6 种 delta 都只进缓冲，但各自仍必须拿到独立的递增 seq");
    }

    @Test
    void milestoneTypesStillFlushImmediately() {
        // 后缀判定的另一侧：非 `*_DELTA` 一律里程碑，必须立即刷出——回放的骨架
        // （turn 起止、块边界、工具调用边界）不能压在缓冲里等攒批。
        when(eventLog.tailSeq("sid-mile")).thenReturn(0);
        var batches = new ArrayList<List<RedisEventLog.Entry>>();
        when(eventLog.appendBatch(anyString(), anyList(), anyInt())).thenAnswer(inv -> {
            List<RedisEventLog.Entry> entries = inv.getArgument(1);
            batches.add(new ArrayList<>(entries));
            return entries.size();
        });

        store.seedSeq("sid-mile");
        for (String type : List.of("AGENT_START", "TEXT_BLOCK_START", "TOOL_CALL_START",
                                   "TOOL_CALL_END", "TEXT_BLOCK_END", "AGENT_END")) {
            store.append("sid-mile", "r", type, "{}");
        }

        // 每个里程碑各自一批（到达时缓冲为空）→ 6 次刷出，而不是全压在最后一起写
        assertEquals(6, batches.size(), "每个里程碑都必须各自立即刷出，实际批次: " + batches);
        for (int i = 0; i < batches.size(); i++) {
            assertEquals(1, batches.get(i).size(),
                "里程碑到达时缓冲为空 → 每批只有它自己，实际: " + batches.get(i));
            assertEquals(i + 1, batches.get(i).get(0).seq(), "批次必须按到达顺序递增");
        }
    }

    @Test
    void milestoneFlushesBufferedDeltasInSameBatch() {
        when(eventLog.tailSeq("sid-buf2")).thenReturn(0);
        var batches = new ArrayList<List<RedisEventLog.Entry>>();
        when(eventLog.appendBatch(anyString(), anyList(), anyInt())).thenAnswer(inv -> {
            List<RedisEventLog.Entry> entries = inv.getArgument(1);
            batches.add(new ArrayList<>(entries));
            return entries.size();
        });

        store.seedSeq("sid-buf2");
        store.append("sid-buf2", "r", "TEXT_BLOCK_DELTA", "{\"delta\":\"a\"}");
        store.append("sid-buf2", "r", "TEXT_BLOCK_DELTA", "{\"delta\":\"b\"}");
        store.append("sid-buf2", "r", "AGENT_END", "{}");

        // 缓冲里的 delta 与触发刷出的里程碑必须在**同一次刷出**里落库，且按 seq 升序：
        // 拆成两次写会造出「里程碑已落库、中间 delta 缺失」的空洞，重连客户端回放时
        // 会把游标推过空洞，永久丢失那段 delta。
        assertEquals(1, batches.size(), "只应刷出一批，实际: " + batches);
        var entries = batches.get(0);
        assertEquals(List.of(1, 2, 3), entries.stream().map(RedisEventLog.Entry::seq).toList(),
            "顺序必须是 缓存的 delta → 里程碑 的 seq 升序");
        assertEquals(List.of("TEXT_BLOCK_DELTA", "TEXT_BLOCK_DELTA", "AGENT_END"),
            entries.stream().map(RedisEventLog.Entry::type).toList());
        verify(eventLog, times(1)).appendBatch(eq("sid-buf2"), anyList(), anyInt());
    }

    @Test
    void finishTurnFlushesPendingRows() {
        when(eventLog.tailSeq("sid-fin")).thenReturn(0);
        when(eventLog.appendBatch(anyString(), anyList(), anyInt()))
            .thenAnswer(inv -> ((List<?>) inv.getArgument(1)).size());

        store.seedSeq("sid-fin");
        store.append("sid-fin", "r", "TEXT_BLOCK_DELTA", "{\"delta\":\"tail\"}");
        store.finishTurn("sid-fin");

        verify(eventLog, times(1)).appendBatch(eq("sid-fin"), anyList(), anyInt());

        // 计数器已释放：下一次 append 重新读一次流顶端播种
        store.append("sid-fin", "r", "TEXT_BLOCK_DELTA", "{\"delta\":\"later\"}");
        verify(eventLog, times(2)).tailSeq(anyString());
    }
}

package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.codec.ServerSentEvent;

import io.agentmanager.framework.config.AgentRedisProperties;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;

/**
 * 跨副本（两个 Pod 共用一个 Redis）集成测试——验证 session_event 从 MySQL 迁到 Redis Streams
 * 之后的**核心设计主张**：因为事件存储现在位于 Redis，一个 session 的 turn 可以被**任意副本**
 * 观察到（{@code /subscribe} 走 {@link SessionEventTailer} 的游标轮询，读的是同一份共享存储），
 * 于是「跨副本正确性」自然成立，不需要任何事件扇出（fan-out）。
 *
 * <p><b>为什么这些用例不能只靠 mock</b>：本文件要钉住的每一条性质都只对真 Redis 成立——
 * XADD 对「ID ≤ 流顶端」的**原子拒绝**（seq 交接的兜底）、流顶端真的能当播种来源（XREVRANGE）、
 * ZSET reply 索引跨连接真的可见、DEL 真的删掉两个 key。用假 Redis 把这些「顺滑」掉之后，
 * 测试就只是在测假实现（与 {@code RedisEventLogIT} 同一取向）。
 *
 * <h2>「两个 Pod」是怎么建模的</h2>
 * 两个**独立的 {@link RedisClient}**（两条独立 TCP 连接）各自包一个 {@link RedisEventLog} 与一个
 * {@link SessionEventStore}，分别叫 {@code podA} / {@code podB}。关键在于两个 store 实例：
 * seq 计数器（{@code seqCounters}）与攒批缓冲（{@code pending}）都是**实例私有字段**，所以
 * 「B 没见过这个 session」在进程内是真的——它没有该 session 的任何计数器与缓冲，
 * 能看到的事件只可能来自共享的 Redis。
 *
 * <p><b>这个模型覆盖不到什么</b>（说清楚边界，避免把本文件当成端到端的多副本验证）：
 * 它不模拟进程崩溃、网络分区、也不覆盖 {@code turn_lease} / {@code confirm_context}
 * （那两个在 MySQL，不在本层）。因此本文件验证的是「存储层 + 尾随器」这一层的跨副本读一致，
 * 不是整条链路的 HA 结论。
 *
 * <p><b>注意 surefire 的 include 是 {@code *Test}/{@code Test*}/{@code *Tests}/{@code *TestCase}，
 * 所以本类（{@code *IT}）不会被 {@code mvn test} 捡到</b>，必须显式指定：
 * <pre>
 *   docker run --rm -d -p 6399:6379 redis:7.2-alpine --appendonly yes --maxmemory-policy noeviction
 *   REDIS_IT=1 REDIS_IT_URL=redis://127.0.0.1:6399 mvn -o test -Dtest=SessionEventStoreCrossReplicaIT
 * </pre>
 *
 * <p>key 前缀固定为 {@code sess:it-<uuid>:*}，每个用例结束即从两个副本各删一遍，不会碰到业务数据。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "REDIS_IT", matches = "true|1")
class SessionEventStoreCrossReplicaIT {

    /** 单 session 流条数上限：本文件只写个位数条，取大值以免 MAXLEN 近似裁剪干扰断言 */
    private static final int MAX_LEN_PER_STREAM = 250_000;

    /**
     * 攒批上限与时间窗都取大值，让「delta 还在缓冲里」成为**确定性事实**而不是与写入速度赛跑：
     * 这样刷出只可能由里程碑事件或 {@link SessionEventStore#finishTurn} 触发，
     * 用例 3 断言的可见性边界才不会偶发（默认 200 行 / 1000ms 也能过，但依赖于用例跑得快）。
     */
    private static final int BATCH_SIZE = 1000;
    private static final int FLUSH_INTERVAL_MS = 60_000;

    /** 观察者轮询间隔：压到 100ms，让跨副本尾随用例在秒级完成 */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    /** 等待上界：观察者流若挂起，测试必须**失败**，而不是把构建挂死 */
    private static final Duration AWAIT = Duration.ofSeconds(15);

    private String url;
    private RedisClient podAClient;
    private RedisClient podBClient;
    private RedisEventLog logA;
    private RedisEventLog logB;
    private SessionEventStore podA;
    private SessionEventStore podB;

    /** 本类创建过的 session id，@AfterEach 逐个清理 */
    private final List<String> sids = new ArrayList<>();

    @BeforeAll
    void setUp() {
        url = System.getenv().getOrDefault("REDIS_IT_URL", "redis://127.0.0.1:6379");
        // 两个 client = 两条独立连接，模型上对应两个 Pod 各自的 Redis 连接；
        // 「共享」的部分只有 Redis 本身，客户端侧不缓存任何东西
        podAClient = newClient(url);
        podBClient = newClient(url);
        logA = new RedisEventLog(podAClient,
            new AgentRedisProperties(url, 2000, 2000, MAX_LEN_PER_STREAM));
        logB = new RedisEventLog(podBClient,
            new AgentRedisProperties(url, 2000, 2000, MAX_LEN_PER_STREAM));
        podA = new SessionEventStore(logA, 7, BATCH_SIZE, FLUSH_INTERVAL_MS);
        podB = new SessionEventStore(logB, 7, BATCH_SIZE, FLUSH_INTERVAL_MS);
    }

    @AfterAll
    void tearDown() {
        podAClient.shutdown();
        podBClient.shutdown();
    }

    /**
     * 删掉本用例建的全部 session key。
     *
     * <p>两个副本各删一遍：deleteSession 幂等（第二次返回 0），而「同样的删除在另一个副本上
     * 也干净」本身就是本文件第 6 条断言的性质——放在清理里等于每次跑都顺带证一遍，
     * 且任何断言失败中断了用例也不会留下 key。
     */
    @AfterEach
    void cleanUp() {
        for (var sid : sids) {
            podA.deleteSession(sid);
            podB.deleteSession(sid);
        }
        sids.clear();
    }

    private static RedisClient newClient(String url) {
        var client = RedisClient.create(RedisURI.create(url));
        client.setOptions(ClientOptions.builder()
            .socketOptions(SocketOptions.builder()
                .connectTimeout(Duration.ofMillis(2000)).build())
            .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(2000)))
            .autoReconnect(true)
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .build());
        return client;
    }

    /** 每个用例独立的 session id（前缀固定 {@code it-}，@AfterEach 整体清理） */
    private String newSession() {
        var sid = "it-" + UUID.randomUUID();
        sids.add(sid);
        return sid;
    }

    // ------------------------------------------------------------ 用例

    /**
     * 用例 1：Pod A 跑完一个 turn（AGENT_START → 两条 delta → AGENT_END）并 finishTurn；
     * Pod B——一个**从未见过这个 session**的 store——从 0 回放，必须拿到全部事件，
     * seq 升序，并以终态帧 AGENT_END 结尾。
     *
     * <p>这就是「客户端断连后重连到**另一个副本**」的路径：B 的进程内没有该 session 的
     * 任何计数器、缓冲或 sink，能拿到这些事件只可能来自共享的 Redis 流。
     */
    @Test
    void podBReplaysPodAsCompletedTurnIncludingTerminalFrame() {
        var sid = newSession();
        var rid = "rid-" + UUID.randomUUID();
        var written = podAWritesCompletedTurn(sid, rid);

        var replayed = replay(podB, sid, null, 0);

        // 与 A 分配的 seq 列表逐项相等：同时钉住「一条不少、一条不多、严格升序、无重复」
        assertEquals(written, seqs(replayed),
            "B 必须收到 A 写的每一条事件，且 seq 一一对应、升序");
        assertEquals(1, replayed.get(0).seq(), "回放从 seq=1（A 的首个事件）开始");
        assertEquals("AGENT_END", replayed.get(replayed.size() - 1).type(),
            "回放必须以终态帧结尾——观察者正是靠它判定 turn 已结束");
        assertEquals(rid, replayed.get(0).replyId(), "replyId 必须跨副本原样读回");
    }

    /**
     * 用例 2：同一个 turn，Pod B 用**turn 中段**的游标续传，必须只拿到 {@code seq > cursor}
     * 的那些事件。
     *
     * <p>少一条 = 断连续传静默丢内容；多一条 = 客户端会重复渲染（前端按 id 去重，但游标的
     * 「已收到到哪」语义被破坏）。所以这里断言的是**精确相等**，不是「包含」。
     */
    @Test
    void podBResumesFromMidTurnCursorAndSeesOnlyTheRemainder() {
        var sid = newSession();
        var rid = "rid-" + UUID.randomUUID();
        var written = podAWritesCompletedTurn(sid, rid);
        int cursor = written.get(1);   // turn 中段：直接取第 2 条事件的 seq

        var replayed = replay(podB, sid, null, cursor);

        assertEquals(written.subList(2, written.size()), seqs(replayed),
            "只能拿到 seq > cursor 的事件，一条不能多、一条不能少");
    }

    /**
     * 用例 3：Pod A 只写 delta（它们进的是 A **进程内**的攒批缓冲，一条都没到 Redis），
     * Pod B 必须什么都看不到；A 的终态路径刷出之后，B 才看得到。
     *
     * <p>这是本层可见性边界的**诚实陈述**：共享存储只共享**已刷出**的行，缓冲是副本私有状态。
     * 由此直接得到「终态必须刷出缓冲」的必要性——turn 的尾巴（最后那批 delta）在刷出之前对
     * 任何其他副本都不存在。生产里真正触发这一次刷出的是**终态里程碑** AGENT_END
     * （非 delta 事件，{@code append} 时把本 session 的缓冲与自身放在**同一批**里刷出），
     * {@link SessionEventStore#finishTurn} 是 turn 结束时的第二条路径。两条都失效的话，
     * 这些行会一直留在执行副本的堆里直到下一个 turn 到来，跨副本订阅者**永久**少一段内容，
     * 而且没有任何报错——本用例走的是 finishTurn 这条（不依赖里程碑的）路径，
     * 因此它能单独钉住 finishTurn 的刷出语义。
     */
    @Test
    void podBDoesNotSeePodAsBufferedRowsUntilTheyAreFlushed() {
        var sid = newSession();
        var rid = "rid-" + UUID.randomUUID();

        assertEquals(1, podA.append(sid, rid, "TEXT_BLOCK_DELTA", delta("buf-1")));
        assertEquals(2, podA.append(sid, rid, "TEXT_BLOCK_DELTA", delta("buf-2")));
        assertEquals(3, podA.append(sid, rid, "TEXT_BLOCK_DELTA", delta("buf-3")));

        // 可见性边界：只 delta、且没到批量/时间窗阈值 ⇒ 缓冲仍在 A 的堆内存里
        assertTrue(replay(podB, sid, null, 0).isEmpty(),
            "B 不该看到 A 的缓冲行——缓冲是副本私有状态，跨副本不可见");
        assertNull(podB.findLatestStrict(sid), "流尚不存在时 B 的 latest 应为 null");
        assertEquals(0, logB.tailSeq(sid), "A 一条都没刷出，B 看到的流顶端必须是 0");

        podA.finishTurn(sid);

        assertEquals(List.of(1, 2, 3), seqs(replay(podB, sid, null, 0)),
            "收尾刷出之后 B 立刻看得见——终态路径必须把缓冲刷进共享存储");
    }

    /**
     * 用例 4（最关键）：seq 跨副本交接不得撞号。
     *
     * <p>Pod A 写满 seq 1..N 后**释放**计数器（{@code releaseSeq}，即「turn 被交给另一个副本」的
     * 原语）；Pod B 从未见过这个 session，播种后继续 append。断言 B 的 seq **> N**（不是从 1 重来），
     * 且 append 返回**正数**——正数才意味着 Redis **接受**了这次 XADD。
     *
     * <p>为什么这条是设计主张的支点：seq 计数器没有任何 Redis key 承载，它**只能**从流顶端
     * （{@link RedisEventLog#tailSeq}）播种。若播种退化（返回 0），B 会分配 seq 1，而 Redis 对
     * 「ID ≤ 流顶端」的 XADD 会**原子拒绝**且整批丢弃，{@code append} 返回 -1——随后每一次
     * 回放/续传都会从这个空洞开始错位。所以「B 拿到 > N 的正 seq」同时证明了播种正确**和**
     * Redis 侧的兜底确实在起作用（本文件里两者只能一起成立）。
     */
    @Test
    void seqHandoverAcrossPodsDoesNotCollide() {
        var sid = newSession();
        var ridA = "rid-" + UUID.randomUUID();
        var writtenByA = podAWritesCompletedTurn(sid, ridA);
        int n = writtenByA.get(writtenByA.size() - 1);

        // turn 交接：A 交出计数器（finishTurn 已释放过，releaseSeq 幂等；这里显式再调一次，
        // 钉住「交接原语本身」而不是它当前的调用时机）
        podA.releaseSeq(sid);

        // B 从未见过这个 session：它的 store 里没有该 session 的计数器，必须自己从流顶端播种
        var ridB = "rid-" + UUID.randomUUID();
        podB.seedSeq(sid);
        int seqB = podB.append(sid, ridB, "AGENT_START", "{\"type\":\"AGENT_START\"}");

        assertTrue(seqB > 0,
            "append 返回正 seq 才说明 Redis 接受了 XADD；-1 表示整批被拒（seq 与流重叠）。实际 " + seqB);
        assertTrue(seqB > n, "B 必须从流顶端续号（> " + n + "），不能从 1 重来。实际 " + seqB);
        assertEquals(n + 1, seqB, "播种自流顶端 ⇒ 下一条恰好是 N+1");

        // 跨副本读回：A 写的事件 + B 刚写的，B 自己都读得到
        assertEquals(seqB, podB.findLatestStrict(sid).seq(), "B 应能读回自己刚写入的那条");
        var all = seqs(replay(podB, sid, null, 0));
        assertEquals(n + 1, all.size(), "流里应是 A 的 " + n + " 条 + B 的 1 条");
        assertEquals(seqB, all.get(all.size() - 1));
    }

    /**
     * 用例 5：真正的 {@code /subscribe} 跨副本场景——Pod B 用 {@link SessionEventTailer} 观察
     * Pod A 已经跑完的 turn：既要把 A 的事件回放出来，又要**在终态处结束流**（而不是永远轮询）。
     *
     * <p>完成判定走的是 {@link SessionEventTailer#probe(String)}：lease 由 mock 提供（已释放），
     * 「最新事件」则是对**共享存储**的真实读取——B 读到 A 写的 AGENT_END ⇒ FINISHED ⇒ 补 done 帧
     * 并 complete。注意回放段本身也会把 AGENT_END 事件发给客户端，但**关闭流**靠的是 probe 对
     * 共享存储的判定：B 的进程内没有 A 的任何 sink 或状态，这正是跨副本能正常关流的原因。
     *
     * <p>lease 与 runtime 用 Mockito 顶掉（它们的实体在 MySQL，不属于本层）；同时断言 lease
     * **被问过**——这是「结束判定确实走了共享存储路径、而不是本地捷径」的正面证据。
     */
    @Test
    void podBSeePodAsTerminalFrameThroughTheTailer() {
        var sid = newSession();
        var rid = "rid-" + UUID.randomUUID();
        var written = podAWritesCompletedTurn(sid, rid);

        // turn 已结束：执行副本已让出租约（probe 的第一跳），confirm_context 无待确认
        var lease = mock(TurnLeaseStore.class);
        var runtime = mock(AgentRuntimeService.class);
        when(lease.isHeld(sid)).thenReturn(false);
        var tailer = new SessionEventTailer(podB, lease, runtime, POLL_INTERVAL);

        // afterSeq=0：先回放（这正是「重连到另一个副本」的路径），再按游标轮询直至终态。
        // 用有界等待——观察到 A 的终态事件却不结束流（挂起）必须让用例失败
        var frames = tailer.tail(sid, rid, 0).collectList().block(AWAIT);
        assertNotNull(frames, "tail 未在有界时间内完成（挂起 = 跨副本观察者永远关不了流）");

        // 帧序：A 的 4 条事件（id = seq）+ 本地补的 done 帧
        assertEquals(written.size() + 1, frames.size(),
            "应是 A 的 " + written.size() + " 条事件 + 1 个 done 帧，实际 " + describe(frames));
        var dataFrames = frames.subList(0, written.size());
        assertEquals(written, dataFrames.stream().map(f -> Integer.valueOf(f.id())).toList(),
            "回放帧的 id 必须是 A 分配的 seq，且顺序一致");
        assertTrue(dataFrames.get(0).data().contains("AGENT_START"),
            "首帧应是 A 写的首个事件：" + describe(frames));
        assertTrue(dataFrames.get(1).data().contains("from-A-1"),
            "帧序必须与 A 的写入顺序一致：" + describe(frames));
        assertTrue(dataFrames.get(dataFrames.size() - 1).data().contains("AGENT_END"),
            "终态帧本身必须送达，不能被 done 帧掩盖：" + describe(frames));
        var last = frames.get(frames.size() - 1);
        assertTrue(last.data() != null && last.data().contains("done"),
            "末帧应是本地补的 done：" + describe(frames));
        assertTrue(last.id() == null, "done 帧不携带 seq（它不是流里的真实事件）");
        // 正面证据：结束判定确实问过租约（即走过 probe → 共享存储），不是本地捷径
        verify(lease, atLeastOnce()).isHeld(sid);
    }

    /**
     * 用例 6：Pod A 的 {@code deleteSession} 必须让 session 对 Pod B **不可见**——事件流与
     * reply 索引两个 key 都要消失。
     *
     * <p>这钉住的是「级联删除是集群级状态，不是副本本地状态」：删除只清本副本的计数器/缓冲
     * 是不够的（换个副本订阅仍旧读得到死数据），必须把共享存储里的 key 删掉。
     * 用例先用 B 的视角做一次**阳性对照**（删之前 B 确实看得到），否则「删完读不到」可能是
     * 因为从来没写进去过，断言就空了。
     */
    @Test
    void deleteSessionFromPodARemovesTheKeysPodBReads() {
        var sid = newSession();
        var rid = "rid-" + UUID.randomUUID();
        var written = podAWritesCompletedTurn(sid, rid);

        // 阳性对照：删除前 B 看得到事件与 reply 索引
        assertEquals(written, seqs(replay(podB, sid, null, 0)));
        assertEquals(List.of(rid), podB.findReplyIds(sid), "删除前 reply 索引对 B 可见");

        assertEquals(2, podA.deleteSession(sid), "events 与 replies 两个 key 都应被删");
        assertEquals(0, logB.tailSeq(sid), "从 B 的连接看，流已不存在");
        assertTrue(logB.replies(sid).isEmpty(), "reply 索引也必须消失");
        assertTrue(replay(podB, sid, null, 0).isEmpty(), "B 的回放应为空");
        assertTrue(podB.findReplyIds(sid).isEmpty(), "history 回填看不到任何 reply");
        assertNull(podB.findLatestStrict(sid), "B 的 latest 应为 null（读不到 ≠ 有事件）");
    }

    // ------------------------------------------------------------ 辅助

    /**
     * Pod A 走完一个完整 turn：AGENT_START（里程碑，立即刷出）→ 两条 delta（攒批）
     * → AGENT_END（里程碑，把缓冲与自身**同批**刷出）→ finishTurn。
     *
     * @return A 分配到的 seq 列表（升序）
     */
    private List<Integer> podAWritesCompletedTurn(String sid, String replyId) {
        var seqs = new ArrayList<Integer>(4);
        seqs.add(podA.append(sid, replyId, "AGENT_START", "{\"type\":\"AGENT_START\"}"));
        seqs.add(podA.append(sid, replyId, "TEXT_BLOCK_DELTA", delta("from-A-1")));
        seqs.add(podA.append(sid, replyId, "TEXT_BLOCK_DELTA", delta("from-A-2")));
        seqs.add(podA.append(sid, replyId, "AGENT_END", "{\"type\":\"AGENT_END\"}"));
        podA.finishTurn(sid);

        // 「A 写成功了」是后面所有断言的前提；若这里就有 -1（整批被拒），后面的断言只会
        // 以更难懂的方式失败
        assertTrue(seqs.stream().allMatch(s -> s > 0), "Pod A 的每条 append 都应拿到正 seq：" + seqs);
        return seqs;
    }

    /** 以某个副本的视角做一次回放查询；有界等待，挂起即失败 */
    private static List<SessionEventStore.EnvelopedEvent> replay(
            SessionEventStore store, String sid, String replyId, int afterSeq) {
        var events = store.queryAfter(sid, replyId, afterSeq).collectList().block(AWAIT);
        assertNotNull(events, "回放查询未在有界时间内完成（挂起 = 跨副本读路径失效）");
        return events;
    }

    private static String delta(String text) {
        return "{\"type\":\"TEXT_BLOCK_DELTA\",\"delta\":\"" + text + "\"}";
    }

    private static List<Integer> seqs(List<SessionEventStore.EnvelopedEvent> events) {
        return events.stream().map(SessionEventStore.EnvelopedEvent::seq).toList();
    }

    /** 失败信息里带上帧的实际内容，省一次复现 */
    private static String describe(List<ServerSentEvent<String>> frames) {
        return frames.stream()
            .map(f -> "id=" + f.id() + " data=" + f.data() + " comment=" + f.comment())
            .toList()
            .toString();
    }
}

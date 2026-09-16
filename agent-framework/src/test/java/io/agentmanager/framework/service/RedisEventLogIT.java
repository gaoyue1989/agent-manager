package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.*;

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

import io.agentmanager.framework.config.AgentRedisProperties;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;

/**
 * {@link RedisEventLog} 集成测试（真实 Redis）。
 *
 * <p>为什么这些用例不能只靠 mock：整个存储层的正确性压在几条**只有真 Redis 才成立**的
 * 语义上——XADD 对非单调 ID 的原子拒绝、游标区间的边界、ZADD NX 保留首个 score、
 * MAXLEN ~ 真的裁、TTL 真的设上。假 Redis 把这些都「顺滑」掉之后，测试就只是在测假实现。
 *
 * <p><b>注意 surefire 的 include 是 {@code *Test}/{@code Test*}/{@code *Tests}/{@code *TestCase}，
 * 所以本类（{@code *IT}）不会被 {@code mvn test} 捡到</b>，必须显式指定：
 * <pre>
 *   docker run --rm -d -p 6399:6379 redis:7.2-alpine
 *   REDIS_IT=1 REDIS_IT_URL=redis://127.0.0.1:6399 mvn test -Dtest=RedisEventLogIT
 * </pre>
 *
 * <p>key 前缀固定为 {@code sess:it-<uuid>:*}，每个用例结束即删，不会碰到真实业务数据。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "REDIS_IT", matches = "true|1")
class RedisEventLogIT {

    /** 每个用例独立的 session id，保证互相隔离且可整体清理 */
    private String sid;
    private RedisClient client;
    private RedisEventLog log;
    private String url;

    @BeforeAll
    void setUp() {
        url = System.getenv().getOrDefault("REDIS_IT_URL", "redis://127.0.0.1:6379");
        var uri = RedisURI.create(url);
        client = RedisClient.create(uri);
        client.setOptions(ClientOptions.builder()
            .socketOptions(SocketOptions.builder()
                .connectTimeout(Duration.ofMillis(2000)).build())
            .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(2000)))
            .autoReconnect(true)
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .build());
    }

    @AfterAll
    void tearDown() {
        client.shutdown();
    }

    /** 删掉本用例的两个 key（顺带验证 deleteSession 在真实 Redis 上确实清干净） */
    @AfterEach
    void cleanUp() {
        if (sid != null && log != null) {
            log.deleteSession(sid);
            sid = null;
        }
    }

    private RedisEventLog newLog(int maxLen) {
        sid = "it-" + UUID.randomUUID();
        // RedisEventLog 只取 maxLenPerStream / commandTimeoutMs，url 由 RedisClient 承载；
        // 这里仍传真实 url，避免读代码时误以为它连的是别处
        log = new RedisEventLog(client, new AgentRedisProperties(url, 2000, 2000, maxLen));
        return log;
    }

    private static List<RedisEventLog.Entry> entries(String replyId, int fromSeq, int count) {
        var out = new ArrayList<RedisEventLog.Entry>(count);
        for (int i = 0; i < count; i++) {
            out.add(new RedisEventLog.Entry(fromSeq + i, "TEXT_BLOCK_DELTA", replyId, "payload-" + (fromSeq + i)));
        }
        return out;
    }

    @Test
    void appendBatchRoundTripsFieldsAndNormalisesBlankReplyIdBackToNull() {
        var l = newLog(250_000);
        assertEquals(3, l.appendBatch(sid, entries("reply-a", 1, 3), 600));

        var got = l.range(sid, 1, null, 100);
        assertEquals(3, got.size());
        assertEquals(1, got.get(0).seq());
        assertEquals("TEXT_BLOCK_DELTA", got.get(0).type());
        assertEquals("payload-1", got.get(0).payload());
        assertEquals("reply-a", got.get(0).replyId(), "replyId 应原样读回");

        // 写入时 null 被存成空串占位，读回**必须还原成 null**：调用方靠 null 判断「无 reply 过滤」
        assertEquals(1, l.appendBatch(sid, entries(null, 4, 1), 600));
        assertNull(l.range(sid, 4, null, 10).get(0).replyId(),
            "空串占位读回后应还原为 null，否则 history 会多出空 reply_id 的脏值");
    }

    /**
     * 游标区间的边界是**最容易错且错了最难发现**的地方：差一格就是「静默少一条事件」。
     * 这里把四种情形都钉住（from 含下界、上界含、上界外为空、空流为空）。
     */
    @Test
    void rangeBoundsAreExact() {
        var l = newLog(250_000);
        l.appendBatch(sid, entries("r", 1, 5), 600);   // seq 1..5

        assertEquals(List.of(1, 2, 3, 4, 5),
            seqs(l.range(sid, 1, null, 100)), "from 是**含**下界（调用方传 afterSeq+1）");
        assertEquals(List.of(4, 5), seqs(l.range(sid, 4, null, 100)));
        assertEquals(List.of(2, 3), seqs(l.range(sid, 2, 3, 100)), "上界是含的");
        assertEquals(List.of(), seqs(l.range(sid, 6, null, 100)), "起点超过流顶端应返回空而不是报错");
        assertEquals(List.of(1, 2), seqs(l.range(sid, 1, null, 2)), "limit 生效");

        // 空流 / 不存在的 session：不能报错
        var other = "it-" + UUID.randomUUID();
        assertEquals(List.of(), seqs(l.range(other, 1, null, 10)));
        assertNull(l.latest(other));
        assertEquals(0, l.tailSeq(other));
        assertEquals(List.of(), l.replies(other));
    }

    @Test
    void tailSeqTracksStreamTopAndIsZeroWhenEmpty() {
        var l = newLog(250_000);
        assertEquals(0, l.tailSeq(sid), "空流的顶端 seq 为 0");
        l.appendBatch(sid, entries("r", 1, 7), 600);
        assertEquals(7, l.tailSeq(sid));
        l.appendBatch(sid, entries("r", 8, 3), 600);
        assertEquals(10, l.tailSeq(sid));
        assertEquals(10, l.latest(sid).seq(), "latest 返回最后一条");
        assertEquals("payload-10", l.latest(sid).payload());
    }

    /**
     * ZADD NX 必须**保留首个 score**（实测：先 5 后 9，结果仍是 5）。
     * 这条支撑着「replyId 的起点 = 该 reply 的首个 seq」，也就是带 replyId 的区间查询。
     */
    @Test
    void repliesIndexKeepsFirstSeqForRepeatedReplyAndSortsByIt() {
        var l = newLog(250_000);
        l.appendBatch(sid, entries("reply-a", 1, 3), 600);
        // 同一个 reply 的后续批次：score 不得被抬高
        l.appendBatch(sid, entries("reply-a", 4, 2), 600);
        l.appendBatch(sid, entries("reply-b", 10, 2), 600);

        var replies = l.replies(sid);
        assertEquals(2, replies.size());
        assertEquals("reply-a", replies.get(0).replyId());
        assertEquals(1, replies.get(0).firstSeq(), "重复写入不得抬高首个 seq");
        assertEquals("reply-b", replies.get(1).replyId());
        assertEquals(10, replies.get(1).firstSeq(), "按首个 seq 升序（等价于 ORDER BY MIN(seq)）");
    }

    @Test
    void deleteSessionRemovesBothKeysAndIsIdempotent() {
        var l = newLog(250_000);
        l.appendBatch(sid, entries("reply-a", 1, 3), 600);
        assertEquals(2, l.deleteSession(sid), "events 与 replies 两个 key 都应被删");
        assertEquals(0, l.tailSeq(sid), "删后流为空");
        assertEquals(List.of(), l.replies(sid));
        assertEquals(0, l.deleteSession(sid), "重复删应返回 0 而不是报错");
    }

    @Test
    void ttlIsAppliedToBothKeys() {
        var l = newLog(250_000);
        l.appendBatch(sid, entries("r", 1, 2), 604_800);
        // 直接读 TTL，断言的是「真的设上了」而不是「我们调用了 EXPIRE」
        try (var c = client.connect()) {
            var s = c.sync();
            long evTtl = s.ttl(RedisEventLog.eventsKey(sid));
            long rpTtl = s.ttl(RedisEventLog.repliesKey(sid));
            assertTrue(evTtl > 600_000 && evTtl <= 604_800, "events TTL 应为 7 天，实际 " + evTtl);
            assertTrue(rpTtl > 600_000 && rpTtl <= 604_800, "replies TTL 应为 7 天，实际 " + rpTtl);
        }
    }

    /**
     * 这是「单写者兜底」在 Redis 侧的等价物：XADD 一条 ID ≤ 流顶端的事件会被**原子拒绝**。
     * 断言两件事——返回 -1（不抛，不阻塞主链路），且日志把它归因为租约/计数器问题。
     */
    @Test
    void nonIncreasingIdReturnsMinusOneAndLogsItAsWriterConflict() {
        var l = newLog(250_000);
        l.appendBatch(sid, entries("r", 1, 3), 600);   // 顶端 = 3

        try (var logs = new LogCapture()) {
            // seq 2 小于顶端 3：等价于「另一个 writer 已经写到了前面」
            assertEquals(-1, l.appendBatch(sid, entries("r", 2, 1), 600));
            assertEquals(3, l.tailSeq(sid), "被拒的批次不得改变流");
            var msgs = logs.messages();
            assertTrue(msgs.stream().anyMatch(m -> m.contains("XADD 被拒")),
                "应记为写入冲突而不是泛泛的失败：" + msgs);
            assertTrue(msgs.stream().anyMatch(m -> m.contains("turn_lease")),
                "日志应给出可执行的排查方向：" + msgs);
        }
    }

    /** MAXLEN 若没真正下发（比如参数拼错），这条会失败——它是「内存兜底真的生效」的看门测试。 */
    @Test
    void maxLenActuallyTrimsTheStream() {
        var l = newLog(100);   // 单 session 上限压到 100 条
        for (int i = 0; i < 10; i++) {
            l.appendBatch(sid, entries("r", 1 + i * 500, 500), 600);
        }
        try (var c = client.connect()) {
            long len = c.sync().xlen(RedisEventLog.eventsKey(sid));
            assertTrue(len < 5000, "MAXLEN ~ 100 应把 5000 条裁到接近 100，实际 " + len);
            assertTrue(len >= 100, "近似裁剪不会裁到 100 以下，实际 " + len);
        }
        // 裁剪不得影响顶端 seq 的读取（tailSeq 走尾部，与裁剪无关）
        assertEquals(5000, l.tailSeq(sid));
    }

    /** 大 payload 原样往返（实测生产里有 3 万字节量级的 tool result）。 */
    @Test
    void largePayloadRoundTripsByteExact() {
        var l = newLog(250_000);
        var big = "x".repeat(30_785);
        assertEquals(1, l.appendBatch(sid,
            List.of(new RedisEventLog.Entry(1, "TOOL_RESULT_TEXT", "r", big)), 600));
        assertEquals(big, l.range(sid, 1, null, 1).get(0).payload());
    }

    /**
     * 自检的**一致性**断言：实际配置是什么，日志就必须说什么。
     *
     * <p>这样写而不是「断言一定报 ERROR」，是为了在两种环境下都成立且都有意义——
     * 本机 6379 是 {@code appendonly no}（应报 ERROR），而按 manifest 起的实例是 {@code yes}
     * （应报「自检通过」）。断言的是两者不矛盾，而不是某一个具体环境的结果。
     */
    @Test
    void selfCheckLogsMatchActualServerConfig() {
        String appendonly;
        String policy;
        try (var c = client.connect()) {
            appendonly = c.sync().configGet("appendonly").get("appendonly");
            policy = c.sync().configGet("maxmemory-policy").get("maxmemory-policy");
        }
        try (var logs = new LogCapture()) {
            newLog(250_000);
            log.tailSeq(sid);   // 触发首次建连 → 自检
            var msgs = logs.messages();

            // 先要**正面证据说明自检确实跑了**：只断言「没报某个错」的话，
            // 自检整个没执行也能通过——那这条测试就白写了。
            assertTrue(msgs.stream().anyMatch(m -> m.contains("RedisEventLog: 持久性自检")),
                "首次建连后必须留下自检的痕迹（通过或告警）：" + msgs);

            boolean compliant = "yes".equalsIgnoreCase(appendonly)
                && "noeviction".equalsIgnoreCase(policy);
            if (compliant) {
                assertTrue(msgs.stream().anyMatch(m -> m.contains("持久性自检通过")),
                    "服务端配置合规，应报「持久性自检通过」：" + msgs);
            } else {
                assertTrue(msgs.stream().anyMatch(m -> m.contains("持久性自检**未通过**")),
                    "服务端配置不合规，应报「持久性自检未通过」：" + msgs);
            }
            if (!"yes".equalsIgnoreCase(appendonly)) {
                assertTrue(msgs.stream().anyMatch(m -> m.contains("appendonly=")),
                    "应报出实际的 appendonly 值：" + msgs);
            }
            if (!"noeviction".equalsIgnoreCase(policy)) {
                assertTrue(msgs.stream().anyMatch(m -> m.contains("maxmemory-policy")),
                    "服务端 policy=" + policy + "，应报淘汰风险：" + msgs);
            }
        }
    }

    private static List<Integer> seqs(List<RedisEventLog.Event> events) {
        return events.stream().map(RedisEventLog.Event::seq).toList();
    }

    /** 把 RedisEventLog 的日志挂到 ListAppender 上；临时放开到 TRACE 以便断言 INFO 级自检通过 */
    private static final class LogCapture implements AutoCloseable {
        private final ch.qos.logback.classic.Logger logger;
        private final ch.qos.logback.classic.Level previous;
        private final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>
            appender = new ch.qos.logback.core.read.ListAppender<>();

        LogCapture() {
            logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(RedisEventLog.class);
            previous = logger.getLevel();
            logger.setLevel(ch.qos.logback.classic.Level.TRACE);
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
            logger.setLevel(previous);
        }
    }
}

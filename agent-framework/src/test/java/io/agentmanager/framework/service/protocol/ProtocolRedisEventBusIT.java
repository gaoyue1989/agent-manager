package io.agentmanager.framework.service.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.agentmanager.framework.config.AgentRedisProperties;
import io.agentmanager.framework.redis.RedisConnectionFacade;
import io.agentscope.harness.agent.subagent.protocol.RemoteAgentEvent;
import io.agentscope.harness.agent.subagent.protocol.RemoteEventType;

/**
 * ProtocolRedisEventBus 真 Redis 集成测试（设计 §18.3；门控同 RedisEventLogIT）：
 * <pre>
 *   docker run --rm -d -p 6399:6379 redis:7.2-alpine
 *   REDIS_IT=1 REDIS_IT_URL=redis://127.0.0.1:6399 mvn test -Dtest=ProtocolRedisEventBusIT
 * </pre>
 *
 * <p>taskId 固定 {@code it-<uuid>}（key 经 facade 前缀隔离），用例结束即删 key。
 * 验证：显式发号 + XADD、fromSeq 回放、XREAD tail 实时性、done 标记收流与 TTL 收窄。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "REDIS_IT", matches = "true|1")
class ProtocolRedisEventBusIT {

    private RedisConnectionFacade facade;
    private ProtocolRedisEventBus bus;
    private String taskId;

    @BeforeAll
    void setUp() {
        var url = System.getenv().getOrDefault("REDIS_IT_URL", "redis://127.0.0.1:6379");
        var prefix = System.getenv().getOrDefault("REDIS_IT_PREFIX", "");
        facade = RedisConnectionFacade.create(
            new AgentRedisProperties(url, 2000, 2000, 250_000,
                AgentRedisProperties.Mode.standalone, "", prefix));
        bus = new ProtocolRedisEventBus(facade);
    }

    @AfterAll
    void tearDown() {
        if (taskId != null) {
            facade.sync().del(bus.eventsKey(taskId));
            facade.sync().del(bus.seqKey(taskId));
            facade.sync().del(bus.doneKey(taskId));
        }
        facade.close();
    }

    private static RemoteAgentEvent event(RemoteEventType type, String text) {
        var e = new RemoteAgentEvent();
        e.setType(type);
        e.setText(text);
        e.setTaskId("it");
        return e;
    }

    @Test
    void publishShouldAssignSeqAndSubscribeShouldReplayAndTail() {
        taskId = "it-" + UUID.randomUUID();

        var e1 = bus.publish(taskId, event(RemoteEventType.TEXT_DELTA, "a"));
        var e2 = bus.publish(taskId, event(RemoteEventType.TEXT_DELTA, "b"));

        assertEquals(1, e1.getSeq(), "INCR 发号从 1 起");
        assertEquals(2, e2.getSeq());

        // fromSeq=1 → 只回放 b；随后 tail 收到实时 c（回放与实时无缝接续）
        var flux = bus.subscribe(taskId, 1);
        bus.publish(taskId, event(RemoteEventType.TEXT_DELTA, "c"));

        var first = flux.filter(x -> "b".equals(x.getText()))
            .blockFirst(Duration.ofSeconds(3));
        assertNotNull(first, "fromSeq 之后的历史帧应回放");

        // tail：done 后流应自然完成（collectList 能结束）
        bus.complete(taskId);
        var all = bus.subscribe(taskId, 0)
            .take(Duration.ofSeconds(5))
            .collectList()
            .block(Duration.ofSeconds(6));
        assertNotNull(all);
        assertTrue(all.size() >= 3, "全量回放应含 a/b/c（实际 " + all.size() + " 帧）");
        assertTrue(all.stream().anyMatch(x -> "c".equals(x.getText())), "tail 应收到实时帧 c");
    }

    @Test
    void completeShouldSetDoneMarkerAndShortenTtl() {
        taskId = "it-" + UUID.randomUUID();
        bus.publish(taskId, event(RemoteEventType.RUN_STARTED, "s"));

        bus.complete(taskId);

        assertEquals("1", facade.sync().get(bus.doneKey(taskId)), "done 标记必须落键");
        Long ttl = facade.sync().ttl(bus.eventsKey(taskId));
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= ProtocolRedisEventBus.COMPLETE_TTL.toSeconds(),
            "complete 后重放窗口应收窄（实际 TTL=" + ttl + "s）");
    }
}

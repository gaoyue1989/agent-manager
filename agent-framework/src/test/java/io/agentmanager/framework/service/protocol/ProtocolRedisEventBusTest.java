package io.agentmanager.framework.service.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.agentmanager.framework.config.AgentRedisProperties;
import io.agentmanager.framework.redis.RedisConnectionFacade;
import io.agentscope.harness.agent.subagent.protocol.RemoteAgentEvent;
import reactor.core.publisher.Flux;

/**
 * ProtocolRedisEventBus 单测（设计 §18.3）：key 形态（前缀 + cluster hash tag）、
 * 无门面纯内存模式（语义等同 SDK 默认实现）、Redis 不可达 fail-soft 降级内存。
 * 真 Redis 行为（发号/回放/tail/done 收流）见 ProtocolRedisEventBusIT（REDIS_IT 门控）。
 */
class ProtocolRedisEventBusTest {

    private static AgentRedisProperties props(String prefix) {
        return new AgentRedisProperties("redis://127.0.0.1:6399" /* 测试环境无监听，快速拒连 */,
            300, 300, 1000, AgentRedisProperties.Mode.standalone, "", prefix);
    }

    private static RemoteAgentEvent event(String text) {
        var e = new RemoteAgentEvent();
        e.setType(io.agentscope.harness.agent.subagent.protocol.RemoteEventType.TEXT_DELTA);
        e.setText(text);
        return e;
    }

    // ===== key 形态 =====

    @Test
    void keysShouldCarryFacadePrefixAndTaskId() {
        var bus = new ProtocolRedisEventBus(RedisConnectionFacade.create(props("svc:")));
        assertEquals("svc:proto:task:t-1:events", bus.eventsKey("t-1"));
        assertEquals("svc:proto:task:t-1:seq", bus.seqKey("t-1"));
        assertEquals("svc:proto:task:t-1:done", bus.doneKey("t-1"));
    }

    @Test
    void clusterModeShouldWrapTaskIdInHashTag() {
        var bus = new ProtocolRedisEventBus(RedisConnectionFacade.create(
            new AgentRedisProperties("redis://127.0.0.1:6399", 300, 300, 1000,
                AgentRedisProperties.Mode.cluster, "redis://127.0.0.1:7000,redis://127.0.0.1:7001", "")));
        assertEquals("proto:task:{t-1}:events", bus.eventsKey("t-1"));
    }

    // ===== 纯内存模式（facade = null）=====

    @Test
    void nullFacadeShouldDelegateToMemoryBus() {
        var bus = new ProtocolRedisEventBus(null);

        var published = bus.publish("t-1", event("hello"));
        assertTrue(published.getSeq() > 0, "内存总线应分配 seq");

        var first = bus.subscribe("t-1", 0).blockFirst(java.time.Duration.ofSeconds(2));
        assertEquals("hello", first != null ? first.getText() : null);
        bus.complete("t-1");   // 不抛出即通过
    }

    // ===== Redis 不可达 fail-soft 降级 =====

    @Test
    void publishShouldDegradeToMemoryWhenRedisUnreachable() {
        // 127.0.0.1:6399 无监听 → 建连失败（退避窗口内快速失败）→ 降级内存总线
        var bus = new ProtocolRedisEventBus(RedisConnectionFacade.create(props("")));

        var published = bus.publish("t-degrade", event("fallback"));
        assertTrue(published.getSeq() > 0, "降级后由内存总线分配 seq，publish 绝不抛出");

        var replayed = bus.subscribe("t-degrade", 0).blockFirst(java.time.Duration.ofSeconds(2));
        assertEquals("fallback", replayed != null ? replayed.getText() : null);
    }

    // ===== 订阅降级路径 =====

    @Test
    void subscribeShouldEmitMemoryReplayAfterDegrade() {
        var bus = new ProtocolRedisEventBus(RedisConnectionFacade.create(props("")));
        bus.publish("t-2", event("a"));
        bus.publish("t-2", event("b"));

        // Redis 不可达：回放探测失败 → 整体降级内存总线。降级订阅以 fromSeq=0 进入
        // （CR P1-2：内存 seq 与 Redis 发号空间断裂，按旧 fromSeq 过滤会永久静默）——
        // 宁重复不静默，故 fromSeq=1 下 seq=1 的 "a" 仍应可见
        var first = bus.subscribe("t-2", 1)
            .filter(e -> e.getText() != null)
            .blockFirst(java.time.Duration.ofSeconds(3));

        assertEquals("a", first != null ? first.getText() : null);
    }
}

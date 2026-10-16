package io.agentmanager.framework.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.agentmanager.framework.model.OafConfig;
import io.agentscope.harness.agent.HarnessAgent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * per-session agent 隔离（上游 #2849 workaround）的缓存语义：
 * 会话命中复用、跨会话隔离、构建失败回退共享实例、LRU/TTL 驱逐、隔离关闭回退。
 */
class AgentRuntimeServiceSessionIsolationTest {

    private static OafConfig config() {
        return new OafConfig(
            "test-agent", "acme", "test-agent", "1.0.0", "acme/test-agent",
            "Test agent", "@acme", "MIT",
            List.of("test"), "You are a test agent.",
            List.of(), List.of(), List.of(), List.of(), List.of(),
            new OafConfig.ModelConfig("openai", "gpt-4", ""),
            new OafConfig.RuntimeConfig(0.7, 4096, false, "default"),
            new OafConfig.MemoryConfig("editable", Map.of()),
            Map.of()
        );
    }

    private static AgentRuntimeService isolatedService(HarnessAgent shared,
                                                       java.util.function.Supplier<HarnessAgent> factory,
                                                       int max, int ttlMinutes) {
        var service = new AgentRuntimeService(config(), shared, List.of(), new LLMLogger(),
            mock(ConfirmContextStore.class), true, max, ttlMinutes);
        service.setSessionAgentFactory(factory);
        return service;
    }

    @Test
    void sameSessionShouldReuseInstance() {
        var shared = mock(HarnessAgent.class);
        var builds = new AtomicInteger();
        var service = isolatedService(shared, () -> {
            builds.incrementAndGet();
            return mock(HarnessAgent.class);
        }, 10, 30);

        assertSame(service.getAgent("sid-1"), service.getAgent("sid-1"));
        assertSame(service.getAgent("sid-1"), service.getAgent("sid-1"));
        assertEquals(1, builds.get(), "同会话重复获取必须命中缓存，不得重复构建");
    }

    @Test
    void differentSessionsShouldGetDistinctInstances() {
        // 核心目的：并行 turn 各持独立 SandboxBackedFilesystem，单例指针不再跨会话互踩
        var service = isolatedService(mock(HarnessAgent.class),
            () -> mock(HarnessAgent.class), 10, 30);

        var a = service.getAgent("sid-a");
        var b = service.getAgent("sid-b");
        assertNotSame(a, b, "不同会话必须拿到不同实例（隔离语义）");
    }

    @Test
    void buildFailureShouldFallBackToSharedAgent() {
        // 会话级构建失败（MCP 全挂等）：回退共享实例，对话可用性优先于隔离
        var shared = mock(HarnessAgent.class);
        var service = isolatedService(shared, () -> {
            throw new IllegalStateException("mcp all down");
        }, 10, 30);

        assertSame(shared, service.getAgent("sid-1"));
    }

    @Test
    void lruShouldEvictOldestWhenOverCapacity() {
        var service = isolatedService(mock(HarnessAgent.class),
            () -> mock(HarnessAgent.class), 2, 30);
        service.getAgent("s1");
        var s2 = service.getAgent("s2");   // 容量 2，s1 为 LRU 头
        service.getAgent("s3");            // 插入触发驱逐：s1 被逐（s2 保留）
        assertEquals(2, service.sessionCacheSize());
        assertSame(s2, service.getAgent("s2"), "s2 未被驱逐，必须命中原实例");
        // s1 已被驱逐：本次 getAgent 会重建 s1 并把 LRU 头（s3）挤出
        var s1Rebuilt = service.getAgent("s1");
        assertEquals(2, service.sessionCacheSize());
        // s1 现在驻留：再次获取命中同一实例
        assertSame(s1Rebuilt, service.getAgent("s1"));
    }

    @Test
    void evictedSessionShouldRebuildInstance() {
        // TTL 归一语义（0 → 默认 30m）不适合短测窗；过期机制与显式驱逐共用同一条
        // remove+rebuild 路径，这里验证驱逐后重建
        var builds = new AtomicInteger();
        var service = isolatedService(mock(HarnessAgent.class), () -> {
            builds.incrementAndGet();
            return mock(HarnessAgent.class);
        }, 10, 30);
        service.getAgent("sid-1");
        assertEquals(1, builds.get());
        service.evictSessionAgent("sid-1");
        service.getAgent("sid-1");
        assertEquals(2, builds.get(), "驱逐后必须重建新实例");
    }

    @Test
    void evictAllShouldInvalidateGeneration() {
        var builds = new AtomicInteger();
        var service = isolatedService(mock(HarnessAgent.class), () -> {
            builds.incrementAndGet();
            return mock(HarnessAgent.class);
        }, 10, 30);
        var first = service.getAgent("sid-1");
        service.evictAllSessionAgents();
        var second = service.getAgent("sid-1");
        assertNotSame(first, second, "reload 世代失效后同会话必须换新实例");
        assertEquals(2, builds.get());
    }

    @Test
    void isolationDisabledShouldAlwaysReturnSharedAgent() {
        // 隔离关闭（默认）：getAgent(sid) 与 getAgent() 同源——不建工厂、不建缓存
        var shared = mock(HarnessAgent.class);
        var service = new AgentRuntimeService(config(), shared, List.of(), new LLMLogger(),
            mock(ConfirmContextStore.class));
        assertFalse(service.sessionIsolationEnabled());
        assertSame(shared, service.getAgent("sid-1"));
        assertSame(shared, service.getAgent(null));
        assertSame(shared, service.getAgent(""));
    }

    @Test
    void missingFactoryShouldFallBackToSharedAgent() {
        // 隔离开启但工厂未注册（装配疏漏防御）：不抛异常，回退共享实例
        var shared = mock(HarnessAgent.class);
        var service = new AgentRuntimeService(config(), shared, List.of(), new LLMLogger(),
            mock(ConfirmContextStore.class), true, 10, 30);
        service.setSessionAgentFactory(null);
        assertSame(shared, service.getAgent("sid-1"));
    }
}

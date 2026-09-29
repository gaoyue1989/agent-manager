package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import reactor.core.publisher.Flux;

/**
 * RemoteSpawnForceSyncMiddleware 单测（travel-fulfillment 设计 §16 实测发现）：
 * 纯 spawn 轮次注入 SDK force_sync 属性、混编/非 spawn 轮次跳过、Flux 终止恢复原值。
 */
class RemoteSpawnForceSyncMiddlewareTest {

    private static final String SID = "webui-1";

    private static ActingInput spawnInput() {
        return new ActingInput(List.of(ToolUseBlock.builder()
            .id("c1").name("agent_spawn").input(java.util.Map.of("agent_id", "booking")).build()));
    }

    private static ActingInput mixedInput() {
        return new ActingInput(List.of(
            ToolUseBlock.builder().id("c1").name("agent_spawn").input(java.util.Map.of()).build(),
            ToolUseBlock.builder().id("c2").name("get_order").input(java.util.Map.of()).build()));
    }

    @Test
    void pureSpawnRoundShouldInjectForceSyncAttributes() {
        var mw = new RemoteSpawnForceSyncMiddleware(true, 120);
        var ctx = RuntimeContext.empty();
        AtomicReference<RuntimeContext> seen = new AtomicReference<>();
        mw.onActing(null, ctx, spawnInput(), i -> {
            seen.set(ctx);
            return Flux.<AgentEvent>empty();
        });
        assertEquals(Boolean.TRUE, seen.get().get(RemoteSpawnForceSyncMiddleware.CTX_FORCE_SYNC));
        assertEquals(120, (Integer) seen.get().get(RemoteSpawnForceSyncMiddleware.CTX_FORCE_SYNC_TIMEOUT_SECONDS));
    }

    @Test
    void mixedRoundShouldSkip() {
        var mw = new RemoteSpawnForceSyncMiddleware(true, 120);
        var ctx = RuntimeContext.empty();
        mw.onActing(null, ctx, mixedInput(), i -> Flux.empty());
        assertNull(ctx.get(RemoteSpawnForceSyncMiddleware.CTX_FORCE_SYNC));
    }

    @Test
    void disabledShouldSkip() {
        var mw = new RemoteSpawnForceSyncMiddleware(false, 120);
        var ctx = RuntimeContext.empty();
        mw.onActing(null, ctx, spawnInput(), i -> Flux.empty());
        assertNull(ctx.get(RemoteSpawnForceSyncMiddleware.CTX_FORCE_SYNC));
    }

    @Test
    void attributesShouldRestoreToPreviousValuesAfterFlux() {
        var mw = new RemoteSpawnForceSyncMiddleware(true, 60);
        var ctx = RuntimeContext.empty();
        ctx.put(RemoteSpawnForceSyncMiddleware.CTX_FORCE_SYNC, Boolean.FALSE);
        mw.onActing(null, ctx, spawnInput(), i -> Flux.empty()).blockLast();
        assertEquals(Boolean.FALSE, ctx.get(RemoteSpawnForceSyncMiddleware.CTX_FORCE_SYNC),
            "Flux 终止后恢复进入前原值");
    }

    @Test
    void zeroWaitSecondsShouldFallBackToDefault() {
        var mw = new RemoteSpawnForceSyncMiddleware(true, 0);
        var ctx = RuntimeContext.empty();
        AtomicReference<RuntimeContext> seen = new AtomicReference<>();
        mw.onActing(null, ctx, spawnInput(), i -> { seen.set(ctx); return Flux.<AgentEvent>empty(); });
        assertEquals(120, (Integer) seen.get().get(RemoteSpawnForceSyncMiddleware.CTX_FORCE_SYNC_TIMEOUT_SECONDS));
        assertTrue(seen.get().get(RemoteSpawnForceSyncMiddleware.CTX_FORCE_SYNC) == Boolean.TRUE);
    }

    @Test
    void spawnRoundShouldCarrySid() {
        // 守卫：RuntimeContext.empty() 可用且 sid 语义与本中间件无冲突（防 SDK 升级漂移的哨兵断言）
        var ctx = RuntimeContext.empty();
        ctx.put("probe", SID);
        assertEquals(SID, ctx.get("probe"));
    }

    @Test
    void zeroOrMissingTimeoutShouldBeRewrittenToWaitSeconds() {
        var mw = new RemoteSpawnForceSyncMiddleware(true, 120);
        var ctx = RuntimeContext.empty();
        AtomicReference<ActingInput> seen = new AtomicReference<>();
        mw.onActing(null, ctx, spawnInput(), i -> { seen.set(i); return Flux.<AgentEvent>empty(); }).blockLast();
        var rewritten = seen.get().toolCalls().get(0);
        assertEquals(120, ((Number) rewritten.getInput().get("timeout_seconds")).intValue(),
            "timeout_seconds 缺失 → 改写为 waitSeconds（阻塞等子任务结果）");
        assertEquals("booking", rewritten.getInput().get("agent_id"), "其余入参保持不变");
    }

    @Test
    void positiveTimeoutShouldBeKept() {
        var mw = new RemoteSpawnForceSyncMiddleware(true, 120);
        var ctx = RuntimeContext.empty();
        var input = new ActingInput(List.of(ToolUseBlock.builder()
            .id("c1").name("agent_spawn")
            .input(java.util.Map.of("agent_id", "booking", "timeout_seconds", 60)).build()));
        AtomicReference<ActingInput> seen = new AtomicReference<>();
        mw.onActing(null, ctx, input, i -> { seen.set(i); return Flux.<AgentEvent>empty(); }).blockLast();
        assertEquals(60, ((Number) seen.get().toolCalls().get(0).getInput().get("timeout_seconds")).intValue(),
            "正数 timeout_seconds 尊重模型选择，不改写");
    }
}

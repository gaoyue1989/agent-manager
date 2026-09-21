package io.agentmanager.framework.mcp;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.agentmanager.framework.service.SessionUserStore;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.tool.mcp.McpMeta;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link McpUserContextMiddleware} 单元测试：userId 解析（Channel 链路 session 反查 /
 * A2A 原值回落 / 反查失败容错）、McpMeta 合并写入、空 ctx 防御。
 */
class McpUserContextMiddlewareTest {

    private static RuntimeContext ctx(String userId) {
        return RuntimeContext.builder().sessionId("s-1").userId(userId).build();
    }

    private static void run(McpUserContextMiddleware mw, RuntimeContext ctx) {
        mw.onAgent(null, ctx, (AgentInput) null, input -> Flux.<AgentEvent>empty()).blockLast();
    }

    @Test
    void shouldResolveRealUserIdFromSessionUserStore() {
        // Channel 链路：RuntimeContext.userId 为网关 peer（=sessionId），反查得到真实用户
        var store = mock(SessionUserStore.class);
        when(store.findUserIdBySession("debug-user_mt1")).thenReturn("alice");
        var mw = new McpUserContextMiddleware(store);

        var context = ctx("debug-user_mt1");
        run(mw, context);

        var meta = context.get(McpMeta.class);
        assertNotNull(meta);
        assertEquals("alice", meta.entries().get(McpUserContextMiddleware.KEY_USER_ID));
    }

    @Test
    void shouldFallbackToRawUserIdWhenSessionNotMapped() {
        // A2A / invoke 链路：userId 即真实用户，反查未命中 → 原值
        var store = mock(SessionUserStore.class);
        when(store.findUserIdBySession("u-42")).thenReturn(null);
        var mw = new McpUserContextMiddleware(store);

        var context = ctx("u-42");
        run(mw, context);

        assertEquals("u-42", context.get(McpMeta.class).entries()
            .get(McpUserContextMiddleware.KEY_USER_ID));
    }

    @Test
    void shouldFallbackWhenStoreThrows() {
        var store = mock(SessionUserStore.class);
        when(store.findUserIdBySession("u-42")).thenThrow(new IllegalStateException("db down"));
        var mw = new McpUserContextMiddleware(store);

        var context = ctx("u-42");
        assertDoesNotThrow(() -> run(mw, context));
        assertEquals("u-42", context.get(McpMeta.class).entries()
            .get(McpUserContextMiddleware.KEY_USER_ID));
    }

    @Test
    void shouldMergeWithExistingMcpMeta() {
        var mw = new McpUserContextMiddleware(null);
        var context = ctx("alice");
        context.put(McpMeta.class, new McpMeta(Map.of("traceId", "t-1", "userId", "stale")));

        run(mw, context);

        var entries = context.get(McpMeta.class).entries();
        assertEquals("t-1", entries.get("traceId"), "已有 entries 不能被覆盖");
        assertEquals("alice", entries.get("userId"), "userId 以本次生效值为准");
    }

    @Test
    void shouldSkipWhenUserIdBlank() {
        var mw = new McpUserContextMiddleware(null);
        var context = ctx("   ");
        run(mw, context);
        assertNull(context.get(McpMeta.class));
    }

    @Test
    void shouldTolerateNullContext() {
        var mw = new McpUserContextMiddleware(null);
        assertDoesNotThrow(() -> run(mw, null));
    }
}

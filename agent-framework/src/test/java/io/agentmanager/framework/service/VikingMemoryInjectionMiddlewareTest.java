package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.agentmanager.framework.config.VikingConfig;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import reactor.core.publisher.Mono;

/**
 * VikingMemoryInjectionMiddleware 单测：query 捕获（ctx 传递）、onSystemPrompt 注入
 * 格式、per-user 缓存、无 userId/无 query 直通。
 */
class VikingMemoryInjectionMiddlewareTest {

    private final VikingMemoryClient client = Mockito.mock(VikingMemoryClient.class);
    private final SessionUserStore store = Mockito.mock(SessionUserStore.class);
    private final SessionKeyResolver resolver = new SessionKeyResolver(store);
    private final VikingConfig cfg = new VikingConfig("openviking", "http://ov:1933",
        "oaf-svc", "ak", "seed", false, false, 2000, 300);

    private RuntimeContext ctxWith(String userId, String sessionId) {
        return RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
    }

    @Test
    void onSystemPromptAppendsMemorySection() {
        var mw = new VikingMemoryInjectionMiddleware(client, resolver, cfg);
        Mockito.when(client.searchContext("u-1", "帮我看看发布流程")).thenReturn("- 偏好:简洁");
        var ctx = ctxWith("u-1", null);
        ctx.put(VikingMemoryInjectionMiddleware.CTX_QUERY_KEY, "帮我看看发布流程");
        var out = mw.onSystemPrompt(null, ctx, "base prompt").block();
        assertTrue(out.startsWith("base prompt"));
        assertTrue(out.contains(VikingMemoryInjectionMiddleware.SECTION_HEADER.trim()));
        assertTrue(out.contains("偏好:简洁"));
    }

    @Test
    void cachedResultSkipsSecondSearch() {
        var mw = new VikingMemoryInjectionMiddleware(client, resolver, cfg);
        Mockito.when(client.searchContext("u-1", "q")).thenReturn("mem");
        var ctx = ctxWith("u-1", null);
        ctx.put(VikingMemoryInjectionMiddleware.CTX_QUERY_KEY, "q");
        mw.onSystemPrompt(null, ctx, "p").block();
        mw.onSystemPrompt(null, ctx, "p").block();
        mw.onSystemPrompt(null, ctx, "p").block();
        Mockito.verify(client, Mockito.times(1)).searchContext(Mockito.eq("u-1"), Mockito.eq("q"));
    }

    @Test
    void noQueryPassesThrough() {
        var mw = new VikingMemoryInjectionMiddleware(client, resolver, cfg);
        var ctx = ctxWith("u-1", null);
        var out = mw.onSystemPrompt(null, ctx, "base").block();
        assertEquals("base", out);
        Mockito.verifyNoInteractions(client);
    }

    @Test
    void noUserIdPassesThrough() {
        var mw = new VikingMemoryInjectionMiddleware(client, resolver, cfg);
        var ctx = RuntimeContext.builder().build();
        var out = mw.onSystemPrompt(null, ctx, "base").block();
        assertEquals("base", out);
        Mockito.verifyNoInteractions(client);
    }

    @Test
    void searchFailurePassesThrough() {
        var mw = new VikingMemoryInjectionMiddleware(client, resolver, cfg);
        Mockito.when(client.searchContext(Mockito.anyString(), Mockito.anyString())).thenReturn(null);
        var ctx = ctxWith("u-1", null);
        ctx.put(VikingMemoryInjectionMiddleware.CTX_QUERY_KEY, "q");
        assertEquals("base", mw.onSystemPrompt(null, ctx, "base").block());
    }
}

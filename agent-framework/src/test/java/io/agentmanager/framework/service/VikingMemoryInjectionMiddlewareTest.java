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

    /**
     * 缓存键必须含会话维度（issue #419 B-07a）：同用户新会话不得复用上一会话的检索结果，
     * 否则新会话注入与当前问题无关的旧记忆、刚写入的记忆 5 分钟内不可见。
     */
    @Test
    void differentSessionsOfSameUserSearchIndependently() {
        var mw = new VikingMemoryInjectionMiddleware(client, resolver, cfg);
        Mockito.when(client.searchContext("u-1", "q")).thenReturn("mem-a", "mem-b");
        var ctx1 = ctxWith("u-1", "s-1");
        ctx1.put(VikingMemoryInjectionMiddleware.CTX_QUERY_KEY, "q");
        var ctx2 = ctxWith("u-1", "s-2");
        ctx2.put(VikingMemoryInjectionMiddleware.CTX_QUERY_KEY, "q");
        mw.onSystemPrompt(null, ctx1, "p").block();
        mw.onSystemPrompt(null, ctx2, "p").block();
        Mockito.verify(client, Mockito.times(2)).searchContext(Mockito.eq("u-1"), Mockito.eq("q"));
        assertTrue(mw.onSystemPrompt(null, ctx1, "p").block().contains("mem-a"));
        assertTrue(mw.onSystemPrompt(null, ctx2, "p").block().contains("mem-b"));
    }

    @Test
    void sameSessionReusesCacheAcrossTurns() {
        var mw = new VikingMemoryInjectionMiddleware(client, resolver, cfg);
        Mockito.when(client.searchContext("u-1", "q")).thenReturn("mem");
        var ctxTurn1 = ctxWith("u-1", "s-1");
        ctxTurn1.put(VikingMemoryInjectionMiddleware.CTX_QUERY_KEY, "q");
        var ctxTurn2 = ctxWith("u-1", "s-1");
        ctxTurn2.put(VikingMemoryInjectionMiddleware.CTX_QUERY_KEY, "q");
        mw.onSystemPrompt(null, ctxTurn1, "p").block();
        mw.onSystemPrompt(null, ctxTurn2, "p").block();
        Mockito.verify(client, Mockito.times(1)).searchContext(Mockito.eq("u-1"), Mockito.eq("q"));
    }
}

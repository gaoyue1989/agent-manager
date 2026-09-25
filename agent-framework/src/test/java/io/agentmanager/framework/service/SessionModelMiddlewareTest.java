package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.Model;
import reactor.core.publisher.Flux;

/**
 * SessionModelMiddleware 单元测试（会话模型切换核心链路）：
 * 命中替换 / 两级 key 回退 / 未设置与不可用回落默认 / 异常兜底（docs/session-model-switch-design.md §7）。
 */
class SessionModelMiddlewareTest {

    private SessionUserStore store;
    private ModelCatalog catalog;
    private SessionModelMiddleware middleware;
    private Model defaultModel;

    @BeforeEach
    void setUp() {
        store = mock(SessionUserStore.class);
        catalog = mock(ModelCatalog.class);
        middleware = new SessionModelMiddleware(store, catalog);
        defaultModel = mock(Model.class);
    }

    @Test
    void shouldRouteBySessionIdWhenMappingExists() {
        var target = mock(Model.class);
        when(store.findModelBySession("sess-1")).thenReturn("m2");
        when(catalog.resolve("m2")).thenReturn(Optional.of(target));

        var used = invoke(ctx("sess-1", "user-1"));

        assertSame(target, used);
    }

    /** Channel 链路（/threads/chat）：前端 sessionId 在 RuntimeContext.userId（网关 peer） */
    @Test
    void shouldFallBackToUserIdKeyForChannelPath() {
        var target = mock(Model.class);
        when(store.findModelBySession("peer:gw-abc")).thenReturn("");
        when(store.findModelBySession("peer")).thenReturn("m2");
        when(catalog.resolve("m2")).thenReturn(Optional.of(target));

        var used = invoke(ctx("peer:gw-abc", "peer"));

        assertSame(target, used);
    }

    @Test
    void shouldPassThroughWhenNoMapping() {
        when(store.findModelBySession(anyString())).thenReturn("");

        var used = invoke(ctx("sess-1", "user-1"));

        assertSame(defaultModel, used);
        verify(catalog, never()).resolve(anyString());
    }

    /** 模型被删除/禁用/未知：回落默认模型，且不抛错 */
    @Test
    void shouldFallBackToDefaultWhenModelUnavailable() {
        when(store.findModelBySession("sess-1")).thenReturn("deleted-model");
        when(catalog.resolve("deleted-model")).thenReturn(Optional.empty());

        var used = invoke(ctx("sess-1", "user-1"));

        assertSame(defaultModel, used);
    }

    @Test
    void shouldPassThroughWhenContextMissing() {
        var used = invoke(null);

        assertSame(defaultModel, used);
    }

    @Test
    void shouldTolerateStoreFailure() {
        when(store.findModelBySession(anyString())).thenThrow(new RuntimeException("db down"));

        var used = invoke(ctx("sess-1", "user-1"));

        assertSame(defaultModel, used);
    }

    // ===== helpers =====

    private static RuntimeContext ctx(String sessionId, String userId) {
        return RuntimeContext.builder().sessionId(sessionId).userId(userId).build();
    }

    /** 记录 next 实际收到的 ModelCallInput，返回其 model */
    private Model invoke(RuntimeContext ctx) {
        var input = new ModelCallInput(List.of(), List.of(), null, defaultModel);
        var captured = new AtomicReference<ModelCallInput>();
        middleware.onModelCall(null, ctx, input, in -> {
            captured.set(in);
            return Flux.empty();
        }).blockLast();
        return captured.get().model();
    }
}

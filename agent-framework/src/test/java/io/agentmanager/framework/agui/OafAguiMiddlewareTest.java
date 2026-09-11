package io.agentmanager.framework.agui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentmanager.framework.service.UiContextStore;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * OafAguiMiddleware 单测（agui-migration-plan 要点 6 / R2）：
 * UiContext 会话注入（RuntimeContext key 触发）、resume 防循环指引追加、
 * 无 key 时 no-op（旧链路共用 bean 不受影响）、异常不阻断。
 */
class OafAguiMiddlewareTest {

    private static final String BASE = "system prompt";

    @Test
    void shouldNoOpWithoutRuntimeKeys() {
        var mw = new OafAguiMiddleware(mock(UiContextStore.class));
        var ctx = RuntimeContext.builder().sessionId("t").userId("u").build();
        assertEquals(BASE, mw.onSystemPrompt(null, ctx, BASE).block());
    }

    @Test
    void shouldNoOpWithNullContext() {
        var mw = new OafAguiMiddleware(mock(UiContextStore.class));
        assertEquals("", mw.onSystemPrompt(null, null, null).block());
    }

    @Test
    void shouldAppendUiContextForSession() {
        var uiContextStore = mock(UiContextStore.class);
        when(uiContextStore.findBySession("t1")).thenReturn(java.util.Optional.of(
            new UiContextStore.UiContext("UI PANEL OPEN", null, null)));
        var mw = new OafAguiMiddleware(uiContextStore);
        var ctx = RuntimeContext.builder().sessionId("t1").userId("u")
            .put(OafAguiMiddleware.RUNTIME_UI_CONTEXT_SESSION_KEY, "t1")
            .build();
        var result = mw.onSystemPrompt(null, ctx, BASE).block();
        assertNotNull(result);
        assertTrue(result.startsWith(BASE));
        assertTrue(result.contains("UI PANEL OPEN"));
    }

    @Test
    void shouldSkipUiContextWhenLookupEmpty() {
        var uiContextStore = mock(UiContextStore.class);
        when(uiContextStore.findBySession("t1")).thenReturn(java.util.Optional.empty());
        var mw = new OafAguiMiddleware(uiContextStore);
        var ctx = RuntimeContext.builder().sessionId("t1").userId("u")
            .put(OafAguiMiddleware.RUNTIME_UI_CONTEXT_SESSION_KEY, "t1")
            .build();
        assertEquals(BASE, mw.onSystemPrompt(null, ctx, BASE).block());
    }

    @Test
    void shouldAppendResumeGuidanceOnResumeRun() {
        var mw = new OafAguiMiddleware(mock(UiContextStore.class));
        var ctx = RuntimeContext.builder().sessionId("t").userId("u")
            .put(io.agentscope.core.agui.adapter.AguiAgentAdapter.RUNTIME_CONTEXT_RESUME_KEY,
                java.util.List.of(mock(Object.class)))
            .build();
        var result = mw.onSystemPrompt(null, ctx, BASE).block();
        assertNotNull(result);
        assertTrue(result.contains("人工确认恢复"));
        assertTrue(result.contains("不得再次调用同一工具"));
    }

    @Test
    void shouldNotAppendResumeGuidanceWithoutResume() {
        var mw = new OafAguiMiddleware(mock(UiContextStore.class));
        var ctx = RuntimeContext.builder().sessionId("t").userId("u")
            .put(io.agentscope.core.agui.adapter.AguiAgentAdapter.RUNTIME_CONTEXT_RESUME_KEY,
                java.util.List.of())
            .build();
        assertEquals(BASE, mw.onSystemPrompt(null, ctx, BASE).block());
    }

    @Test
    void shouldFallBackToPromptOnInjectionFailure() {
        var uiContextStore = mock(UiContextStore.class);
        when(uiContextStore.findBySession("t1")).thenThrow(new RuntimeException("db down"));
        var mw = new OafAguiMiddleware(uiContextStore);
        var ctx = RuntimeContext.builder().sessionId("t1").userId("u")
            .put(OafAguiMiddleware.RUNTIME_UI_CONTEXT_SESSION_KEY, "t1")
            .build();
        assertEquals(BASE, mw.onSystemPrompt(null, ctx, BASE).block());
    }
}

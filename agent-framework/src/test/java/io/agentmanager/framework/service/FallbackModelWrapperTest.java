package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.agentscope.core.agent.config.FailoverListener;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import reactor.core.publisher.Flux;

/**
 * FallbackModelWrapper 测试：主模型首信号失败切备用、正常不切、已产出后失败不切、
 * 元信息委托与空备用退化为直返主模型（与 SDK modelForCall 语义对齐）。
 */
class FallbackModelWrapperTest {

    private static ChatResponse response(String text) {
        return new ChatResponse(text, List.of(), new ChatUsage(1, 1, 2, 0.0), null, "stop");
    }

    private static Model model(String name) {
        var model = mock(Model.class);
        when(model.getModelName()).thenReturn(name);
        return model;
    }

    @Test
    void shouldSwitchToFallbackWhenPrimaryFailsOnFirstSignal() {
        var primary = model("primary");
        var fallback = model("fallback");
        var fallbackResp = response("from-fallback");
        when(primary.stream(any(), any(), any()))
            .thenReturn(Flux.error(new RuntimeException("429 too many requests")));
        when(fallback.stream(any(), any(), any())).thenReturn(Flux.just(fallbackResp));

        var wrapper = new FallbackModelWrapper(primary, fallback);
        var result = wrapper.stream(List.of(), List.of(), null).collectList().block();

        assertEquals(1, result.size());
        assertSame(fallbackResp, result.get(0));
        verify(fallback).stream(any(), any(), any());
    }

    @Test
    void shouldNotSwitchWhenPrimarySucceeds() {
        var primary = model("primary");
        var fallback = model("fallback");
        var primaryResp = response("primary-ok");
        when(primary.stream(any(), any(), any())).thenReturn(Flux.just(primaryResp));

        var wrapper = new FallbackModelWrapper(primary, fallback);
        var result = wrapper.stream(List.of(), List.of(), null).collectList().block();

        assertSame(primaryResp, result.get(0));
        verify(fallback, never()).stream(any(), any(), any());
    }

    /** 与 SDK 一致：仅首个信号为 error 才切换；已产出增量后再失败不切换，错误照常传播 */
    @Test
    void shouldNotSwitchAfterPartialEmission() {
        var primary = model("primary");
        var fallback = model("fallback");
        when(primary.stream(any(), any(), any()))
            .thenReturn(Flux.concat(Flux.just(response("partial")),
                Flux.error(new RuntimeException("mid-stream reset"))));
        when(fallback.stream(any(), any(), any())).thenReturn(Flux.just(response("from-fallback")));

        var wrapper = new FallbackModelWrapper(primary, fallback);
        var flux = wrapper.stream(List.of(), List.of(), null);

        assertThrows(RuntimeException.class, flux::blockLast);
        verify(fallback, never()).stream(any(), any(), any());
    }

    @Test
    void shouldDelegateMetadataToPrimary() {
        var primary = model("primary");
        var fallback = model("fallback");
        when(primary.supportsNativeStructuredOutput()).thenReturn(true);
        when(primary.supportsNativeStructuredOutputWithTools()).thenReturn(false);
        when(primary.getContextWindowSize()).thenReturn(131072);

        var wrapper = new FallbackModelWrapper(primary, fallback);
        assertEquals("primary", wrapper.getModelName());
        assertEquals(true, wrapper.supportsNativeStructuredOutput());
        assertEquals(false, wrapper.supportsNativeStructuredOutputWithTools());
        assertEquals(131072, wrapper.getContextWindowSize());
    }

    @Test
    void wrapWithNullFallbackReturnsPrimaryDirectly() {
        var primary = model("primary");
        assertSame(primary, FallbackModelWrapper.wrap(primary, null));
    }

    /** 对齐上游 #3149：primary.stream() 同步抛出（连接池耗尽/DNS 失败等装配期故障）也要走首信号切换 */
    @Test
    void shouldSwitchToFallbackWhenPrimaryStreamSetupThrowsSynchronously() {
        var primary = model("primary");
        var fallback = model("fallback");
        var fallbackResp = response("from-fallback");
        when(primary.stream(any(), any(), any()))
            .thenThrow(new IllegalStateException("dial tcp: connection refused"));
        when(fallback.stream(any(), any(), any())).thenReturn(Flux.just(fallbackResp));

        var wrapper = new FallbackModelWrapper(primary, fallback);
        var result = wrapper.stream(List.of(), List.of(), null).collectList().block();

        assertEquals(1, result.size());
        assertSame(fallbackResp, result.get(0));
        verify(fallback).stream(any(), any(), any());
    }

    @Test
    void shouldNotifyFailoverListenerAtSwitchSite() {
        var primary = model("primary");
        var fallback = model("fallback");
        when(primary.stream(any(), any(), any()))
            .thenReturn(Flux.error(new RuntimeException("429 too many requests")));
        when(fallback.stream(any(), any(), any())).thenReturn(Flux.just(response("from-fallback")));
        var listener = mock(FailoverListener.class);

        var wrapper = new FallbackModelWrapper(primary, fallback, listener);
        var result = wrapper.stream(List.of(), List.of(), null).collectList().block();

        assertEquals(1, result.size());
        var captor = ArgumentCaptor.forClass(Throwable.class);
        verify(listener).onFailover(same(primary), captor.capture());
        assertEquals("429 too many requests", captor.getValue().getMessage());
    }

    @Test
    void shouldNotNotifyListenerWhenPrimarySucceeds() {
        var primary = model("primary");
        var fallback = model("fallback");
        when(primary.stream(any(), any(), any())).thenReturn(Flux.just(response("primary-ok")));
        var listener = mock(FailoverListener.class);

        var wrapper = new FallbackModelWrapper(primary, fallback, listener);
        wrapper.stream(List.of(), List.of(), null).collectList().block();

        verify(listener, never()).onFailover(any(), any());
    }

    /** 上游 notifyFailover 语义：listener 抛出的异常被吞掉，切换与 fallback 调用不受影响 */
    @Test
    void shouldContainListenerExceptionAndProceedWithFallback() {
        var primary = model("primary");
        var fallback = model("fallback");
        var fallbackResp = response("from-fallback");
        when(primary.stream(any(), any(), any()))
            .thenReturn(Flux.error(new RuntimeException("429 too many requests")));
        when(fallback.stream(any(), any(), any())).thenReturn(Flux.just(fallbackResp));
        var listener = mock(FailoverListener.class);
        doThrow(new RuntimeException("listener blew up")).when(listener).onFailover(isA(Model.class), isA(Throwable.class));

        var wrapper = new FallbackModelWrapper(primary, fallback, listener);
        var result = wrapper.stream(List.of(), List.of(), null).collectList().block();

        assertEquals(1, result.size());
        assertSame(fallbackResp, result.get(0));
        verify(listener).onFailover(same(primary), isA(Throwable.class));
    }
}

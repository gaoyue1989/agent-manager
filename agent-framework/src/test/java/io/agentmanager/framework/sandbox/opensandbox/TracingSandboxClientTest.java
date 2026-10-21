package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxApiException;
import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxError;

import io.agentmanager.framework.TracingTestBase;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;

/**
 * TracingSandboxClient 测试：create/resume/delete 的 span 创建与错误路径。
 */
class TracingSandboxClientTest extends TracingTestBase {

    @Test
    void shouldCreateSpanOnCreateSuccess() {
        var delegate = mock(OpenSandboxClient.class);
        var options = mock(OpenSandboxClientOptions.class);
        when(options.getImage()).thenReturn("opensandbox/code-interpreter:v1.1.0");
        var result = mock(Sandbox.class);
        var state = new OpenSandboxState();
        state.setSandboxId("sbx-1");
        when(result.getState()).thenReturn(state);
        when(delegate.create(any(), any(), any())).thenReturn(result);

        var client = new TracingSandboxClient(delegate);
        var out = client.create(mock(WorkspaceSpec.class), mock(SandboxSnapshotSpec.class), options);

        assertSame(result, out);
        var spans = findSpans("sandbox.create");
        assertEquals(1, spans.size());
        assertEquals(StatusCode.OK, spans.get(0).getStatus().getStatusCode());
        assertEquals("opensandbox/code-interpreter:v1.1.0",
                spans.get(0).getAttributes().get(AttributeKey.stringKey("sandbox.image")));
        assertEquals("sbx-1", spans.get(0).getAttributes().get(AttributeKey.stringKey("sandbox.id")));
    }

    @Test
    void shouldRecordErrorWhenCreateFails() {
        var delegate = mock(OpenSandboxClient.class);
        when(delegate.create(any(), any(), any()))
                .thenThrow(new RuntimeException("remote sandbox unavailable"));
        var options = mock(OpenSandboxClientOptions.class);

        var client = new TracingSandboxClient(delegate);
        assertThrows(RuntimeException.class,
                () -> client.create(null, null, options));

        var spans = findSpans("sandbox.create");
        assertEquals(1, spans.size());
        assertEquals(StatusCode.ERROR, spans.get(0).getStatus().getStatusCode());
        assertEquals(1, spans.get(0).getEvents().size());  // recordException
    }

    @Test
    void shouldCreateSpanOnResumeWithSandboxId() {
        var delegate = mock(OpenSandboxClient.class);
        var state = new OpenSandboxState();
        state.setSandboxId("sbx-2");
        var result = mock(Sandbox.class);
        when(result.getState()).thenReturn(state);
        when(delegate.resume(any())).thenReturn(result);

        var client = new TracingSandboxClient(delegate);
        client.resume(state);

        var spans = findSpans("sandbox.resume");
        assertEquals(1, spans.size());
        assertEquals(StatusCode.OK, spans.get(0).getStatus().getStatusCode());
        assertEquals("sbx-2", spans.get(0).getAttributes().get(AttributeKey.stringKey("sandbox.id")));
    }

    @Test
    void shouldMarkExpiredInsteadOfErrorWhenResume404() {
        var delegate = mock(OpenSandboxClient.class);
        when(delegate.resume(any())).thenThrow(sandboxNotFound());
        var state = new OpenSandboxState();
        state.setSandboxId("sbx-4");

        var client = new TracingSandboxClient(delegate);
        assertThrows(SandboxApiException.class, () -> client.resume(state));

        var spans = findSpans("sandbox.resume");
        assertEquals(1, spans.size());
        // 404 = 沙箱被 TTL 回收，acquire 降级 create 是预期路径 → span 正常而非 ERROR
        assertEquals(StatusCode.OK, spans.get(0).getStatus().getStatusCode());
        assertTrue(spans.get(0).getAttributes()
                .get(AttributeKey.booleanKey("sandbox.resume.expired")));
        assertEquals(1, spans.get(0).getEvents().size());
        assertEquals("sandbox not found (expired/GC), acquire falls back to create",
                spans.get(0).getEvents().get(0).getName());
    }

    @Test
    void shouldDetect404WrappedInCauseChain() {
        var delegate = mock(OpenSandboxClient.class);
        when(delegate.resume(any()))
                .thenThrow(new IllegalStateException("connect failed", sandboxNotFound()));
        var state = new OpenSandboxState();
        state.setSandboxId("sbx-5");

        var client = new TracingSandboxClient(delegate);
        assertThrows(IllegalStateException.class, () -> client.resume(state));

        var spans = findSpans("sandbox.resume");
        assertEquals(1, spans.size());
        assertEquals(StatusCode.OK, spans.get(0).getStatus().getStatusCode());
        assertTrue(spans.get(0).getAttributes()
                .get(AttributeKey.booleanKey("sandbox.resume.expired")));
    }

    @Test
    void shouldRecordErrorWhenResumeFailsNon404() {
        var delegate = mock(OpenSandboxClient.class);
        when(delegate.resume(any())).thenThrow(new SandboxApiException("boom", null, 500,
                new SandboxError("INTERNAL", "boom"), null, null, false));
        var state = new OpenSandboxState();
        state.setSandboxId("sbx-6");

        var client = new TracingSandboxClient(delegate);
        assertThrows(SandboxApiException.class, () -> client.resume(state));

        var spans = findSpans("sandbox.resume");
        assertEquals(1, spans.size());
        // 非 404 错误维持 ERROR + recordException，不被"过期重建正常化"掩盖
        assertEquals(StatusCode.ERROR, spans.get(0).getStatus().getStatusCode());
        assertEquals(1, spans.get(0).getEvents().size());
    }

    /** 构造「沙箱 id 不存在」异常（error 参数为 SDK 唯一非空校验项） */
    private static SandboxApiException sandboxNotFound() {
        return new SandboxApiException("sandbox not found", null, 404,
                new SandboxError("NOT_FOUND", "sandbox not found"), null, null, false);
    }

    @Test
    void shouldCreateSpanOnDeleteAndDelegate() {
        var delegate = mock(OpenSandboxClient.class);
        var sandbox = mock(Sandbox.class);
        var state = new OpenSandboxState();
        state.setSandboxId("sbx-3");
        when(sandbox.getState()).thenReturn(state);

        var client = new TracingSandboxClient(delegate);
        client.delete(sandbox);
        verify(delegate).delete(sandbox);

        var spans = findSpans("sandbox.delete");
        assertEquals(1, spans.size());
        assertEquals(StatusCode.OK, spans.get(0).getStatus().getStatusCode());
        assertEquals("sbx-3", spans.get(0).getAttributes().get(AttributeKey.stringKey("sandbox.id")));
    }

    @Test
    void shouldHandleUnknownStateWithoutSandboxId() {
        var delegate = mock(OpenSandboxClient.class);
        var result = mock(Sandbox.class);
        when(result.getState()).thenReturn(mock(SandboxState.class));  // 非 OpenSandboxState
        when(delegate.create(any(), any(), any())).thenReturn(result);

        var client = new TracingSandboxClient(delegate);
        client.create(mock(WorkspaceSpec.class), null, mock(OpenSandboxClientOptions.class));

        var spans = findSpans("sandbox.create");
        assertEquals(1, spans.size());
        assertEquals("", spans.get(0).getAttributes().get(AttributeKey.stringKey("sandbox.id")));
    }

    @Test
    void shouldDelegateSerializeDeserialize() {
        var delegate = mock(OpenSandboxClient.class);
        var client = new TracingSandboxClient(delegate);
        var state = new OpenSandboxState();

        client.serializeState(state);
        verify(delegate).serializeState(state);

        when(delegate.deserializeState("{}")).thenReturn(state);
        assertSame(state, client.deserializeState("{}"));
    }
}
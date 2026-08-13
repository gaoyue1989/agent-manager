package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

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
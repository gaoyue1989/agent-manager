package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxEndpoint;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxInfo;
import com.alibaba.opensandbox.sandbox.domain.services.Commands;
import com.alibaba.opensandbox.sandbox.domain.services.Filesystem;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.ExecutionLogs;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.OutputMessage;

import io.agentmanager.framework.service.WorkspaceReader;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;

class OpenSandboxClientTest {

    private static OpenSandboxClientOptions options() {
        return new OpenSandboxClientOptions()
            .serverUrl("192.168.31.155:8090")
            .apiKey("k")
            .image("opensandbox/code-interpreter:v1.1.0")
            .timeout(Duration.ofMinutes(60))
            .resource(Map.of("cpu", "1", "memory", "1024Mi"));
    }

    /** AbstractBaseSandbox 构造要求 state.workspaceSpec 非空 */
    private static OpenSandboxState state() {
        var s = new OpenSandboxState();
        var ws = new WorkspaceSpec();
        ws.setRoot("/workspace");
        s.setWorkspaceSpec(ws);
        return s;
    }

    /** 构造 mock SDK Sandbox（含 commands/files/endpoint/info） */
    private static Sandbox mockOsbSandbox(String id) {
        var osb = mock(Sandbox.class);
        var info = mock(SandboxInfo.class);
        when(info.getId()).thenReturn(id);
        when(osb.getInfo()).thenReturn(info);

        var endpoint = mock(SandboxEndpoint.class);
        when(endpoint.getEndpoint()).thenReturn("192.168.31.155:52051/proxy/44772");
        when(osb.getEndpoint(anyInt())).thenReturn(endpoint);

        var commands = mock(Commands.class);
        var exec = mock(Execution.class);
        when(exec.getExitCode()).thenReturn(0);
        var logs = mock(ExecutionLogs.class);
        when(logs.getStdout()).thenReturn(List.of(new OutputMessage("ok", 0L, false)));
        when(logs.getStderr()).thenReturn(List.of());
        when(exec.getLogs()).thenReturn(logs);
        when(commands.run(anyString())).thenReturn(exec);
        when(osb.commands()).thenReturn(commands);

        var files = mock(Filesystem.class);
        when(osb.files()).thenReturn(files);
        return osb;
    }

    @Test
    void createShouldBuildSandboxWithState() {
        var osb = mockOsbSandbox("sb-created-1");
        try (MockedStatic<Sandbox> mocked = mockStatic(Sandbox.class)) {
            var builder = mock(Sandbox.Builder.class);
            when(builder.connectionConfig(any())).thenReturn(builder);
            when(builder.image(anyString())).thenReturn(builder);
            when(builder.timeout(any())).thenReturn(builder);
            when(builder.resource(anyMap())).thenReturn(builder);
            when(builder.env(anyMap())).thenReturn(builder);
            when(builder.build()).thenReturn(osb);
            mocked.when(Sandbox::builder).thenReturn(builder);

            var client = new OpenSandboxClient(options(), mock(WorkspaceReader.class), null);
            var sandbox = client.create(new WorkspaceSpec(), null, options());

            assertInstanceOf(OpenSandbox.class, sandbox);
            var state = ((OpenSandbox) sandbox).getOsbState();
            assertEquals("sb-created-1", state.getSandboxId());
            assertEquals("192.168.31.155:52051/proxy/44772", state.getSandboxEndpoint());
            assertEquals("opensandbox/code-interpreter:v1.1.0", state.getImage());
            assertTrue(state.getCreatedAt() > 0);
        }
    }

    @Test
    void createShouldPassWorkspaceReaderToSandbox() {
        var osb = mockOsbSandbox("sb-rw");
        var reader = mock(WorkspaceReader.class);
        try (MockedStatic<Sandbox> mocked = mockStatic(Sandbox.class)) {
            var builder = mock(Sandbox.Builder.class);
            when(builder.connectionConfig(any())).thenReturn(builder);
            when(builder.image(anyString())).thenReturn(builder);
            when(builder.timeout(any())).thenReturn(builder);
            when(builder.resource(anyMap())).thenReturn(builder);
            when(builder.env(anyMap())).thenReturn(builder);
            when(builder.build()).thenReturn(osb);
            mocked.when(Sandbox::builder).thenReturn(builder);

            var client = new OpenSandboxClient(options(), reader, null);
            var sandbox = (OpenSandbox) client.create(new WorkspaceSpec(), null, options());

            // 延迟注入不发生在 create（无 userId），验证不抛异常且句柄可访问
            assertEquals(osb, sandbox.getOsbSandbox());
        }
    }

    @Test
    void resumeShouldReturnOpenSandbox() {
        var osb = mockOsbSandbox("sb-resumed-1");
        try (MockedStatic<Sandbox> mocked = mockStatic(Sandbox.class)) {
            var connector = mock(Sandbox.Connector.class);
            when(connector.sandboxId(anyString())).thenReturn(connector);
            when(connector.connectionConfig(any())).thenReturn(connector);
            when(connector.skipHealthCheck(anyBoolean())).thenReturn(connector);
            when(connector.connect()).thenReturn(osb);
            mocked.when(Sandbox::connector).thenReturn(connector);

            var client = new OpenSandboxClient(options(), null, null);
            var resumeState = state();
            resumeState.setSandboxId("sb-resumed-1");

            var sandbox = client.resume(resumeState);

            assertInstanceOf(OpenSandbox.class, sandbox);
            assertEquals(osb, ((OpenSandbox) sandbox).getOsbSandbox());
            verify(connector).connect();
        }
    }

    @Test
    void deleteShouldKillAndClose() {
        var osb = mockOsbSandbox("sb-delete");
        var client = new OpenSandboxClient(options(), null, null);
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        client.delete(sandbox);

        verify(osb).kill();
        verify(osb).close();
    }

    @Test
    void serializeStateShouldRoundTrip() throws Exception {
        var client = new OpenSandboxClient(options(), null, null);
        var state = new OpenSandboxState();
        state.setSandboxId("sb-rt");
        state.setUserId("u1");
        state.setImage("img:1");
        state.setCreatedAt(123L);

        var json = client.serializeState(state);
        var restored = (OpenSandboxState) client.deserializeState(json);

        assertEquals("sb-rt", restored.getSandboxId());
        assertEquals("u1", restored.getUserId());
        assertEquals("img:1", restored.getImage());
        assertEquals(123L, restored.getCreatedAt());
    }

    @Test
    void deserializeStateShouldRejectMalformedJson() {
        var client = new OpenSandboxClient(options(), null, null);
        assertThrows(io.agentscope.harness.agent.sandbox.SandboxException.class,
            () -> client.deserializeState("{bad"));
    }
}

package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
            .resource(Map.of("cpu", "1", "memory", "1024Mi"))
            .resourceRequests(Map.of("cpu", "100m", "memory", "128Mi"));
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
            when(builder.resourceRequests(anyMap())).thenReturn(builder);
            when(builder.env(anyMap())).thenReturn(builder);
            when(builder.build()).thenReturn(osb);
            mocked.when(Sandbox::builder).thenReturn(builder);

            var client = new OpenSandboxClient(options(), mock(WorkspaceReader.class), null);
            var sandbox = client.create(new WorkspaceSpec(), null, options());

            // D19（独立于池开关生效）：池关闭路径同样透传 Pod requests
            verify(builder).resourceRequests(eq(Map.of("cpu", "100m", "memory", "128Mi")));
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
            when(builder.resourceRequests(anyMap())).thenReturn(builder);
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

    // ---------------------------------------------------------------- 池化分支（sandbox-pool-design D1/D2/D13，§8 开关对照）

    /** 构造池管理器 stub：borrow 返回指定容器、resume 校正放行 */
    private static SandboxPoolManager poolStub(String borrowId) {
        var pool = mock(SandboxPoolManager.class);
        var osb = mockOsbSandbox(borrowId);
        when(pool.borrow(any(), any())).thenReturn(new SandboxPoolManager.Borrowed(borrowId, osb));   // poolSid/userKey 可为 null
        when(pool.reconcileResume(anyString(), any(), any())).thenReturn(true);
        return pool;
    }

    @Test
    void createWithPoolShouldBorrowAndInjectCurrentWorkspaceSpec() {
        var pool = poolStub("sb-pooled-1");
        var client = new OpenSandboxClient(options(), mock(WorkspaceReader.class), null);
        client.setSandboxPoolManager(pool);

        var spec = new WorkspaceSpec();
        spec.setRoot("/workspace");
        var sandbox = (OpenSandbox) client.create(spec, null, options());

        // D2：sandboxId = 借出容器 id；workspaceSpec = 当次传入（start 投影 hydrate 依赖）
        assertEquals("sb-pooled-1", sandbox.getOsbState().getSandboxId());
        assertEquals("/workspace", sandbox.getOsbState().getWorkspaceSpec().getRoot());
        verify(pool).borrow(any(), any());   // poolSid/userKey 可为 null（无 middleware/守卫 ThreadLocal 时）
        verify(pool, never()).release(anyString());
    }

    @Test
    void createWithPoolShouldPassSessionIdAndUserKeyFromThreadLocal() {
        // D17 P1：借出身份（sid=turn_lease 键、userKey=真实用户）从 spec ThreadLocal 传入 borrow
        var pool = poolStub("sb-pooled-2");
        var fsSpec = new OpenSandboxFilesystemSpec();
        var client = new OpenSandboxClient(options(), mock(WorkspaceReader.class), null, fsSpec);
        client.setSandboxPoolManager(pool);
        fsSpec.setPendingSessionId("sid-abc");
        fsSpec.setPendingUserKey("alice");

        var spec = new WorkspaceSpec();
        spec.setRoot("/workspace");
        client.create(spec, null, options());

        verify(pool).borrow("sid-abc", "alice");
    }

    @Test
    void createWithoutPoolShouldKeepLegacyPath() {
        // 开关关闭（默认）：不触池，行为与 master 逐分支一致（现状 create 真建容器）
        var osb = mockOsbSandbox("sb-legacy");
        var pool = mock(SandboxPoolManager.class);
        var client = new OpenSandboxClient(options(), mock(WorkspaceReader.class), null);
        try (MockedStatic<Sandbox> mocked = mockStatic(Sandbox.class)) {
            var builder = mock(Sandbox.Builder.class);
            when(builder.connectionConfig(any())).thenReturn(builder);
            when(builder.image(anyString())).thenReturn(builder);
            when(builder.timeout(any())).thenReturn(builder);
            when(builder.resource(anyMap())).thenReturn(builder);
            when(builder.resourceRequests(anyMap())).thenReturn(builder);
            when(builder.env(anyMap())).thenReturn(builder);
            when(builder.build()).thenReturn(osb);
            mocked.when(Sandbox::builder).thenReturn(builder);
            var sandbox = (OpenSandbox) client.create(state().getWorkspaceSpec(), null, options());
            assertEquals("sb-legacy", sandbox.getOsbState().getSandboxId());
        }
        verify(pool, never()).borrow(any(), any());
    }

    @Test
    void resumeWithPoolShouldReconcileAndRenewLease() {
        var pool = poolStub("sb-pooled-1");
        var osb = mockOsbSandbox("sb-pooled-1");
        var client = new OpenSandboxClient(options(), mock(WorkspaceReader.class), null);
        client.setSandboxPoolManager(pool);
        try (MockedStatic<Sandbox> mocked = mockStatic(Sandbox.class)) {
            var connector = mock(Sandbox.Connector.class);
            when(connector.sandboxId(anyString())).thenReturn(connector);
            when(connector.connectionConfig(any())).thenReturn(connector);
            when(connector.skipHealthCheck(anyBoolean())).thenReturn(connector);
            when(connector.connect()).thenReturn(osb);
            mocked.when(Sandbox::connector).thenReturn(connector);

            var resumeState = state();
            resumeState.setSandboxId("sb-pooled-1");
            client.resume(resumeState);
            // 续约②：connect 成功 → 账本校正放行 → 跨副本全局键保活
            verify(pool).reconcileResume(eq("sb-pooled-1"), any(), any());
            verify(pool).renew("sb-pooled-1");
        }
    }

    @Test
    void resumeWithPoolShouldDowngradeWhenReconcileFails() {
        // P4 顺序修正：校正不过（被继承给他人/已回收）→ close 句柄 + 抛异常触发 SDK P3→P4 降级
        var pool = mock(SandboxPoolManager.class);
        when(pool.reconcileResume(anyString(), any(), any())).thenReturn(false);
        var osb = mockOsbSandbox("sb-stolen");
        var client = new OpenSandboxClient(options(), mock(WorkspaceReader.class), null);
        client.setSandboxPoolManager(pool);
        try (MockedStatic<Sandbox> mocked = mockStatic(Sandbox.class)) {
            var connector = mock(Sandbox.Connector.class);
            when(connector.sandboxId(anyString())).thenReturn(connector);
            when(connector.connectionConfig(any())).thenReturn(connector);
            when(connector.skipHealthCheck(anyBoolean())).thenReturn(connector);
            when(connector.connect()).thenReturn(osb);
            mocked.when(Sandbox::connector).thenReturn(connector);

            var resumeState = state();
            resumeState.setSandboxId("sb-stolen");
            assertThrows(io.agentscope.harness.agent.sandbox.SandboxException.class,
                () -> client.resume(resumeState));
            // 降级前必须 close 已 connect 句柄（SDK Sandbox AutoCloseable），且不续约不写账本
            verify(osb).close();
            verify(pool, never()).renew(anyString());
        }
    }

    @Test
    void resumeWithoutPoolShouldNotTouchPool() {
        var osb = mockOsbSandbox("sb-r2");
        var pool = mock(SandboxPoolManager.class);
        var client = new OpenSandboxClient(options(), null, null);
        try (MockedStatic<Sandbox> mocked = mockStatic(Sandbox.class)) {
            var connector = mock(Sandbox.Connector.class);
            when(connector.sandboxId(anyString())).thenReturn(connector);
            when(connector.connectionConfig(any())).thenReturn(connector);
            when(connector.skipHealthCheck(anyBoolean())).thenReturn(connector);
            when(connector.connect()).thenReturn(osb);
            mocked.when(Sandbox::connector).thenReturn(connector);
            var resumeState = state();
            resumeState.setSandboxId("sb-r2");
            client.resume(resumeState);
        }
        verify(pool, never()).renew(anyString());
    }

    @Test
    void deleteWithPoolShouldRouteIntoPool_d13() {
        // D13：delete 路由进池（kill + 认领 + 异步补位），不直杀句柄
        var pool = mock(SandboxPoolManager.class);
        var osb = mockOsbSandbox("sb-pooled-1");
        var client = new OpenSandboxClient(options(), null, null);
        client.setSandboxPoolManager(pool);
        var st = state();
        st.setSandboxId("sb-pooled-1");
        var sandbox = new OpenSandbox(st, osb, options(), null, null);

        client.delete(sandbox);

        verify(pool).release("sb-pooled-1");
        verify(osb, never()).kill();   // kill 由池统一执行（含认领/补位），不走现状直杀
    }

    @Test
    void deleteWithoutPoolShouldKillDirectly() {
        // 开关关闭：现状直杀（回归对照）
        var osb = mockOsbSandbox("sb-del2");
        var client = new OpenSandboxClient(options(), null, null);
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);
        client.delete(sandbox);
        verify(osb).kill();
        verify(osb).close();
    }
}

package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
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
import org.mockito.ArgumentCaptor;
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

    // ==================== U4（sandbox-pool-opensandbox-design §9.1）====================

    private static final String SID = "sess-u4";
    private static final String USER_KEY = "user-u4";

    /** 带生命周期（mock manager）+ 身份捕获的 spec */
    private OpenSandboxFilesystemSpec lifecycleSpec(SandboxLifecycleManager manager) {
        var spec = new OpenSandboxFilesystemSpec()
            .serverUrl("s:8090").apiKey("k").image("img:1")
            .timeout(Duration.ofMinutes(30));
        spec.setLifecycleManager(manager);
        spec.setPendingSessionId(SID);
        spec.setPendingUserKey(USER_KEY);
        return spec;
    }

    @Test
    void createWithoutLifecycleKeepsBaselineShape() {
        // lifecycle 旁路：builder 走 image/resource，无 extensions/metadata（基线行为零回归）
        var osb = mockOsbSandbox("sb-base");
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
            client.create(new WorkspaceSpec(), null, options());

            verify(builder).image(anyString());
            verify(builder).resource(anyMap());
            verify(builder, never()).extensions(anyMap());
            verify(builder, never()).metadata(anyMap());
        }
    }

    @Test
    void createWithPoolRefInjectsExtensionsAndSuppressesResource() throws Exception {
        // §4.2 ③ + 评审 d17：poolRef 非空 → extensions.poolRef 透传、resourceLimits 抑制、
        // entrypoint/env 保留（taskTemplate 下发通路）、timeout=TTL。image 必传：SDK
        // Sandbox.Builder.build() 客户端强约束 image XOR snapshotId（1.0.18 实证），
        // poolRef 路径无法抑制（上游对组合实测忽略，镜像以 Pool 模板为准）
        var manager = mock(SandboxLifecycleManager.class);
        when(manager.config()).thenReturn(new io.agentmanager.framework.config.SandboxLifecycleConfig(
            true, "pool-e2e", 5, 60, 20));
        when(manager.acquire(anyString(), anyString(), any(), any(), any()))
            .thenAnswer(inv -> {
                // 直调 create 委托：穿透到 builder 组装逻辑
                var delegate = inv.getArgument(3, SandboxLifecycleManager.SandboxCreateDelegate.class);
                return delegate.create().sandbox();
            });
        var osb = mockOsbSandbox("sb-pool");
        var info = mock(com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxInfo.class);
        when(info.getId()).thenReturn("sb-pool");
        when(osb.getInfo()).thenReturn(info);

        try (MockedStatic<Sandbox> mocked = mockStatic(Sandbox.class)) {
            var builder = mock(Sandbox.Builder.class);
            when(builder.connectionConfig(any())).thenReturn(builder);
            when(builder.image(anyString())).thenReturn(builder);
            when(builder.timeout(any())).thenReturn(builder);
            when(builder.env(anyMap())).thenReturn(builder);
            when(builder.entrypoint(any(java.util.List.class))).thenReturn(builder);
            when(builder.extensions(anyMap())).thenReturn(builder);
            when(builder.metadata(anyMap())).thenReturn(builder);
            when(builder.build()).thenReturn(osb);
            mocked.when(Sandbox::builder).thenReturn(builder);

            var client = new OpenSandboxClient(options(), mock(WorkspaceReader.class), null, lifecycleSpec(manager));
            client.create(new WorkspaceSpec(), null, options());

            verify(builder).extensions(eq(Map.of("poolRef", "pool-e2e")));
            verify(builder).image(anyString());   // SDK 客户端强约束必传（上游忽略）
            verify(builder, never()).resource(anyMap());
            verify(builder).entrypoint(any(java.util.List.class));   // env/entrypoint 保留
            verify(builder).timeout(any(java.time.Duration.class));
            var meta = ArgumentCaptor.forClass(Map.class);
            verify(builder).metadata(meta.capture());
            @SuppressWarnings("unchecked")
            Map<String, String> metaMap = (Map<String, String>) meta.getValue();
            assertEquals(SID, metaMap.get("session_id"), "metadata.session_id = 记账会话键");
            // metadata.service：env HOST_NAME 无 k8s 形态的测试环境无 service 键；有则必非空
            if (metaMap.containsKey("service")) {
                assertFalse(metaMap.get("service").isBlank());
            }
            verify(manager).acquire(eq(SID), eq(USER_KEY), any(), any(), any());
        }
    }

    @Test
    void createWithoutPoolRefPassesImageAndResource() throws Exception {
        // poolRef 空（默认）→ create 不带 extensions.poolRef，走普通创建（§4.5 兼容存量）
        var manager = mock(SandboxLifecycleManager.class);
        when(manager.config()).thenReturn(new io.agentmanager.framework.config.SandboxLifecycleConfig(
            true, "", 5, 60, 20));
        when(manager.acquire(anyString(), anyString(), any(), any(), any()))
            .thenAnswer(inv -> {
                var delegate = inv.getArgument(3, SandboxLifecycleManager.SandboxCreateDelegate.class);
                return delegate.create().sandbox();
            });
        var osb = mockOsbSandbox("sb-nopool");
        try (MockedStatic<Sandbox> mocked = mockStatic(Sandbox.class)) {
            var builder = mock(Sandbox.Builder.class);
            when(builder.connectionConfig(any())).thenReturn(builder);
            when(builder.image(anyString())).thenReturn(builder);
            when(builder.timeout(any())).thenReturn(builder);
            when(builder.resource(anyMap())).thenReturn(builder);
            when(builder.env(anyMap())).thenReturn(builder);
            when(builder.entrypoint(any(java.util.List.class))).thenReturn(builder);
            when(builder.metadata(anyMap())).thenReturn(builder);
            when(builder.build()).thenReturn(osb);
            mocked.when(Sandbox::builder).thenReturn(builder);

            var client = new OpenSandboxClient(options(), mock(WorkspaceReader.class), null, lifecycleSpec(manager));
            client.create(new WorkspaceSpec(), null, options());

            verify(builder).image(anyString());
            verify(builder).resource(anyMap());
            verify(builder, never()).extensions(anyMap());
        }
    }

    @Test
    void createRoutesQuotaExhaustedThroughToCaller() throws Exception {
        // §4.6：acquire 全忙异常原样透传（SSE error 帧载荷由控制器错误回调组装）
        var manager = mock(SandboxLifecycleManager.class);
        when(manager.acquire(anyString(), anyString(), any(), any(), any()))
            .thenThrow(new UserQuotaExhaustedException(5, List.of(
                new UserQuotaExhaustedException.BindingView(SID, "2026-11-08T00:00:00Z", true, false, null))));
        var client = new OpenSandboxClient(options(), mock(WorkspaceReader.class), null, lifecycleSpec(manager));

        var ex = assertThrows(UserQuotaExhaustedException.class,
            () -> client.create(new WorkspaceSpec(), null, options()));
        assertEquals(5, ex.quota());
    }

    @Test
    void createIdentityMissingFallsBackToBaseline() throws Exception {
        // 守卫未装配的防御分支（身份缺失）→ 基线 create，不路由 lifecycle
        var manager = mock(SandboxLifecycleManager.class);
        when(manager.config()).thenReturn(new io.agentmanager.framework.config.SandboxLifecycleConfig(
            true, "", 5, 60, 20));
        var spec = lifecycleSpec(manager);
        spec.takePendingSessionId();   // 清空捕获
        var osb = mockOsbSandbox("sb-noid");
        try (MockedStatic<Sandbox> mocked = mockStatic(Sandbox.class)) {
            var builder = mock(Sandbox.Builder.class);
            when(builder.connectionConfig(any())).thenReturn(builder);
            when(builder.image(anyString())).thenReturn(builder);
            when(builder.timeout(any())).thenReturn(builder);
            when(builder.resource(anyMap())).thenReturn(builder);
            when(builder.env(anyMap())).thenReturn(builder);
            when(builder.build()).thenReturn(osb);
            mocked.when(Sandbox::builder).thenReturn(builder);

            var client = new OpenSandboxClient(options(), mock(WorkspaceReader.class), null, spec);
            client.create(new WorkspaceSpec(), null, options());
            verify(manager, never()).acquire(anyString(), anyString(), any(), any(), any());
        }
    }

    @Test
    void resumeOwnershipOkRegistersCallRef() throws Exception {
        // U4 三分支之一：归属匹配 → 条件写 affected=1（P3 通过即 touch）→ 放行 + acquireRef
        var store = mock(SandboxBindingStore.class);
        var manager = mock(SandboxLifecycleManager.class);
        when(manager.bindingStore()).thenReturn(store);
        when(store.occupyWrite(anyString(), anyList())).thenReturn(true);
        var spec = lifecycleSpec(manager);
        var osb = mockOsbSandbox("sb-resume-lc");
        try (MockedStatic<Sandbox> mocked = mockStatic(Sandbox.class)) {
            var connector = mock(Sandbox.Connector.class);
            when(connector.sandboxId(anyString())).thenReturn(connector);
            when(connector.connectionConfig(any())).thenReturn(connector);
            when(connector.skipHealthCheck(anyBoolean())).thenReturn(connector);
            when(connector.connect()).thenReturn(osb);
            mocked.when(Sandbox::connector).thenReturn(connector);

            var client = new OpenSandboxClient(options(), mock(WorkspaceReader.class), null, spec);
            var resumeState = state();
            resumeState.setSandboxId("sb-resume-lc");
            client.resume(resumeState);

            var sidCap = ArgumentCaptor.forClass(String.class);
            var idsCap = ArgumentCaptor.forClass(List.class);
            verify(store).occupyWrite(sidCap.capture(), idsCap.capture());
            assertEquals("sb-resume-lc", sidCap.getValue());
            assertEquals(List.of(SID), idsCap.getValue());   // UPDATE 载荷 WHERE sandbox_id AND session_id
            verify(manager).onResumeAcquired(eq("sb-resume-lc"), eq(SID), any());
        }
    }

    @Test
    void resumeOwnershipLostClosesAndDegrades() throws Exception {
        // U4 三分支之二：无行/归他会话/漂移 → 条件写 affected=0 → close 句柄 + 降级异常
        var store = mock(SandboxBindingStore.class);
        var manager = mock(SandboxLifecycleManager.class);
        when(manager.bindingStore()).thenReturn(store);
        when(store.occupyWrite(anyString(), anyList())).thenReturn(false);
        var spec = lifecycleSpec(manager);
        var osb = mockOsbSandbox("sb-stolen");
        try (MockedStatic<Sandbox> mocked = mockStatic(Sandbox.class)) {
            var connector = mock(Sandbox.Connector.class);
            when(connector.sandboxId(anyString())).thenReturn(connector);
            when(connector.connectionConfig(any())).thenReturn(connector);
            when(connector.skipHealthCheck(anyBoolean())).thenReturn(connector);
            when(connector.connect()).thenReturn(osb);
            mocked.when(Sandbox::connector).thenReturn(connector);

            var client = new OpenSandboxClient(options(), mock(WorkspaceReader.class), null, spec);
            var resumeState = state();
            resumeState.setSandboxId("sb-stolen");
            assertThrows(io.agentscope.harness.agent.sandbox.SandboxException.class,
                () -> client.resume(resumeState));
            verify(osb).close();   // 受害降级路径 close（未执行任何命令，无实害）
            verify(manager, never()).onResumeAcquired(anyString(), anyString(), any());
            // 身份归还：harness 降级 create 重走决策时生命周期记账仍拿得到 sid
            //（不还回去 = 重建沙箱脱簿记，2026-11-08 lifecycle 组本地实跑实锤）
            assertEquals(SID, spec.peekPendingSessionId());
        }
    }

    @Test
    void resumeConnect404RegistersRebuildNoticeAndKeepsIdentity() throws Exception {
        // V6⑧ live 实锤缺失路径：绑定行被 renew-404 sweep 懒剔除后沙箱 GC，confirm/chat
        // 恢复 P3 connect 404 → 不经归属拦截 → 设计 §4.4「① miss → 重建 + 换代提示帧」
        // 分支此前漏发。修复：connect 404/410 时登记换代（异常原样重抛，降级 create 语义不变）
        var manager = mock(SandboxLifecycleManager.class);
        var spec = lifecycleSpec(manager);   // ThreadLocal sid 已置
        var osb = mock(Sandbox.class);
        try (MockedStatic<Sandbox> mocked = mockStatic(Sandbox.class)) {
            var connector = mock(Sandbox.Connector.class);
            when(connector.sandboxId(anyString())).thenReturn(connector);
            when(connector.connectionConfig(any())).thenReturn(connector);
            when(connector.skipHealthCheck(anyBoolean())).thenReturn(connector);
            when(connector.connect()).thenThrow(new com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxApiException(
                "gone", null, 404, mock(com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxError.class)));
            mocked.when(Sandbox::connector).thenReturn(connector);

            var client = new OpenSandboxClient(options(), mock(WorkspaceReader.class), null, spec);
            var resumeState = state();
            resumeState.setSandboxId("sb-gc");
            assertThrows(com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxApiException.class,
                () -> client.resume(resumeState));   // 基线降级语义不变（原样上抛，harness 捕获降级）
            verify(manager).markSandboxRebuilt(SID);   // 换代登记
            // ThreadLocal sid 未被 take：harness 降级 create 的生命周期记账仍拿得到 sid
            assertEquals(SID, spec.peekPendingSessionId());
            verify(manager, never()).onResumeAcquired(anyString(), anyString(), any());
        }
    }

    @Test
    void resumeOwnershipCheckUnavailableFailsOpen() throws Exception {
        // U4 三分支之三：校验写异常（DataAccessException）→ 放行 + WARN（fail-open）
        var store = mock(SandboxBindingStore.class);
        var manager = mock(SandboxLifecycleManager.class);
        when(manager.bindingStore()).thenReturn(store);
        when(store.occupyWrite(anyString(), anyList()))
            .thenThrow(new SandboxBindingStore.DataAccessException("db down", null));
        var spec = lifecycleSpec(manager);
        var osb = mockOsbSandbox("sb-resume-fo");
        try (MockedStatic<Sandbox> mocked = mockStatic(Sandbox.class)) {
            var connector = mock(Sandbox.Connector.class);
            when(connector.sandboxId(anyString())).thenReturn(connector);
            when(connector.connectionConfig(any())).thenReturn(connector);
            when(connector.skipHealthCheck(anyBoolean())).thenReturn(connector);
            when(connector.connect()).thenReturn(osb);
            mocked.when(Sandbox::connector).thenReturn(connector);

            var client = new OpenSandboxClient(options(), mock(WorkspaceReader.class), null, spec);
            var resumeState = state();
            resumeState.setSandboxId("sb-resume-fo");
            var sandbox = client.resume(resumeState);
            assertInstanceOf(OpenSandbox.class, sandbox);   // 放行
            verify(manager).recordOwnershipFailOpen(eq("sb-resume-fo"), eq(SID), any());
        }
    }

    @Test
    void newConfigBindingChainDefaultsAlignWithDesign() {
        // §4.7 绑定链：env → application.yml 占位符段 → 构造器 null 归一（@DefaultValue 即归一）
        var cfg = new io.agentmanager.framework.config.SandboxLifecycleConfig(true, "", 5, 60, 20);
        assertTrue(cfg.enabled());
        assertEquals("", cfg.poolRef());
        assertEquals(5, cfg.userQuota());
        assertEquals(60, cfg.renewIntervalSeconds());
        assertEquals(20, cfg.confirmHoldMinutes());
    }
}

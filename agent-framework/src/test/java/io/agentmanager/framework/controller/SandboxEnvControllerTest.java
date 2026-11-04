package io.agentmanager.framework.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.alibaba.opensandbox.sandbox.Sandbox;

import io.agentmanager.framework.sandbox.opensandbox.LocatedSandbox;
import io.agentmanager.framework.sandbox.opensandbox.RedisSandboxEnvLock;
import io.agentmanager.framework.sandbox.opensandbox.SandboxEnvException;
import io.agentmanager.framework.sandbox.opensandbox.SandboxEnvFile;
import io.agentmanager.framework.sandbox.opensandbox.SandboxLocator;
import io.agentmanager.framework.sandbox.opensandbox.OpenSandboxFilesystemSpec;
import io.agentmanager.framework.service.SandboxRuntime;
import io.agentscope.harness.agent.IsolationScope;

/**
 * {@link SandboxEnvController} 契约测试（MockMvc standalone）：身份/入参校验（400）、
 * 沙箱未启用/隔离范围不支持/EXECD_ENVS 不可用（409）、定位失败（404）、沙箱不可达（502）、
 * 写租约等待超时（503 env_update_busy，issue #230）、成功响应形状（含 applied=next_exec、
 * 响应不含 env 值）与 {@link LocatedSandbox} 句柄关闭。
 */
class SandboxEnvControllerTest {

    private static final String USER = "u-1";
    private static final String SESSION = "sess-1";
    private static final String ENV_PATH = "/opt/opensandbox/.env";

    private SandboxRuntime runtime;
    private SandboxLocator locator;
    private SandboxEnvFile envFile;
    private RedisSandboxEnvLock envLock;
    private RedisSandboxEnvLock.Lease lease;
    private Sandbox handle;
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        runtime = mock(SandboxRuntime.class);
        when(runtime.enabled()).thenReturn(true);
        locator = mock(SandboxLocator.class);
        envFile = mock(SandboxEnvFile.class);
        handle = mock(Sandbox.class);
        envLock = mock(RedisSandboxEnvLock.class);
        lease = mock(RedisSandboxEnvLock.Lease.class);
        when(envLock.acquire(anyString())).thenReturn(lease);

        OpenSandboxFilesystemSpec spec = mock(OpenSandboxFilesystemSpec.class);
        when(spec.getIsolationScope()).thenReturn(IsolationScope.USER);
        ObjectProvider<OpenSandboxFilesystemSpec> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(spec);

        mvc = MockMvcBuilders.standaloneSetup(
            new SandboxEnvController(runtime, locator, envFile, provider, envLock)).build();
    }

    private void locatedAt(String sandboxId) {
        when(locator.locate(eq(USER), eq(SESSION), any())).thenReturn(new LocatedSandbox(sandboxId, handle));
    }

    private void mergeReturns(Map<String, String> managed, List<String> updated) {
        when(envFile.discoverEnvPath(handle)).thenReturn(ENV_PATH);
        when(envFile.mergeAndWrite(eq(handle), eq(ENV_PATH), anyMap()))
            .thenReturn(new SandboxEnvFile.MergeResult(managed, updated));
    }

    // ---------- 成功 ----------

    @Test
    void updateShouldReturnMergedKeysWithoutValues() throws Exception {
        locatedAt("sbx-1");
        mergeReturns(new LinkedHashMap<>(Map.of("A", "1", "B", "2")), List.of("B"));

        mvc.perform(put("/sandbox/env").contentType(MediaType.APPLICATION_JSON).content("""
            {"userId":"u-1","sessionId":"sess-1","env":{"B":"2"}}
            """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value("u-1"))
            .andExpect(jsonPath("$.sessionId").value("sess-1"))
            .andExpect(jsonPath("$.sandboxId").value("sbx-1"))
            .andExpect(jsonPath("$.updatedKeys[0]").value("B"))
            .andExpect(jsonPath("$.keys.length()").value(2))
            .andExpect(jsonPath("$.count").value(2))
            .andExpect(jsonPath("$.applied").value("next_exec"));
        // 响应永不回显 env 值
        verify(handle).close();
    }

    // ---------- 入参校验（400） ----------

    @Test
    void updateShouldRejectMissingUserId() throws Exception {
        mvc.perform(put("/sandbox/env").contentType(MediaType.APPLICATION_JSON).content("""
            {"sessionId":"sess-1","env":{"A":"1"}}
            """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_user_id"));
    }

    @Test
    void updateShouldRejectMissingSessionId() throws Exception {
        mvc.perform(put("/sandbox/env").contentType(MediaType.APPLICATION_JSON).content("""
            {"userId":"u-1","env":{"A":"1"}}
            """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_session_id"));
    }

    @Test
    void updateShouldRejectEmptyEnv() throws Exception {
        mvc.perform(put("/sandbox/env").contentType(MediaType.APPLICATION_JSON).content("""
            {"userId":"u-1","sessionId":"sess-1","env":{}}
            """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_env"));
    }

    @Test
    void updateShouldRejectMissingEnv() throws Exception {
        mvc.perform(put("/sandbox/env").contentType(MediaType.APPLICATION_JSON).content("""
            {"userId":"u-1","sessionId":"sess-1"}
            """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_env"));
    }

    // ---------- 沙箱状态（409/404/502） ----------

    @Test
    void updateShouldReturn409WhenSandboxDisabled() throws Exception {
        when(runtime.enabled()).thenReturn(false);
        mvc.perform(put("/sandbox/env").contentType(MediaType.APPLICATION_JSON).content("""
            {"userId":"u-1","sessionId":"sess-1","env":{"A":"1"}}
            """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("sandbox_disabled"));
    }

    @Test
    void updateShouldReturn404WhenSandboxMissing() throws Exception {
        when(locator.locate(anyString(), anyString(), any()))
            .thenThrow(new SandboxEnvException("sandbox_not_found", 404, "no live sandbox"));
        mvc.perform(put("/sandbox/env").contentType(MediaType.APPLICATION_JSON).content("""
            {"userId":"u-1","sessionId":"sess-1","env":{"A":"1"}}
            """))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("sandbox_not_found"));
    }

    @Test
    void updateShouldReturn409WhenIsolationScopeUnsupported() throws Exception {
        when(locator.locate(anyString(), anyString(), any()))
            .thenThrow(new SandboxEnvException("unsupported_isolation_scope", 409, "AGENT"));
        mvc.perform(put("/sandbox/env").contentType(MediaType.APPLICATION_JSON).content("""
            {"userId":"u-1","sessionId":"sess-1","env":{"A":"1"}}
            """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("unsupported_isolation_scope"));
    }

    @Test
    void updateShouldReturn409WhenExecdEnvsUnsupported() throws Exception {
        locatedAt("sbx-1");
        when(envFile.discoverEnvPath(handle))
            .thenThrow(new SandboxEnvException("execd_env_file_unsupported", 409, "EXECD_ENVS empty"));
        mvc.perform(put("/sandbox/env").contentType(MediaType.APPLICATION_JSON).content("""
            {"userId":"u-1","sessionId":"sess-1","env":{"A":"1"}}
            """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("execd_env_file_unsupported"));
    }

    @Test
    void updateShouldPropagateValueConstraintError() throws Exception {
        locatedAt("sbx-1");
        when(envFile.discoverEnvPath(handle)).thenReturn(ENV_PATH);
        when(envFile.mergeAndWrite(any(), anyString(), anyMap()))
            .thenThrow(new SandboxEnvException("unsupported_env_value", 400, "single quote"));
        mvc.perform(put("/sandbox/env").contentType(MediaType.APPLICATION_JSON).content("""
            {"userId":"u-1","sessionId":"sess-1","env":{"A":"it's"}}
            """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("unsupported_env_value"));
    }

    // ---------- 写租约（issue #230）----------

    @Test
    void updateShouldReturn503WhenEnvLeaseBusy() throws Exception {
        locatedAt("sbx-1");
        when(envLock.acquire("sbx-1"))
            .thenThrow(new SandboxEnvException("env_update_busy", 503, "lease held by another update"));
        mvc.perform(put("/sandbox/env").contentType(MediaType.APPLICATION_JSON).content("""
            {"userId":"u-1","sessionId":"sess-1","env":{"A":"1"}}
            """))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").value("env_update_busy"));
    }

    @Test
    void updateShouldHoldLeaseAcrossMergeAndReleaseAfterwards() throws Exception {
        // 临界区覆盖断言：acquire 先于 discover/mergeAndWrite，close 在合并完成（或失败）后
        locatedAt("sbx-1");
        when(envFile.discoverEnvPath(handle)).thenReturn(ENV_PATH);
        when(envFile.mergeAndWrite(any(), anyString(), anyMap()))
            .thenReturn(new SandboxEnvFile.MergeResult(Map.of("A", "1"), List.of("A")));

        mvc.perform(put("/sandbox/env").contentType(MediaType.APPLICATION_JSON).content("""
            {"userId":"u-1","sessionId":"sess-1","env":{"A":"1"}}
            """))
            .andExpect(status().isOk());

        var order = inOrder(envLock, lease, envFile);
        order.verify(envLock).acquire("sbx-1");
        order.verify(envFile).discoverEnvPath(handle);
        order.verify(envFile).mergeAndWrite(any(), anyString(), anyMap());
        order.verify(lease).close();
    }

    @Test
    void updateShouldReleaseLeaseWhenMergeFails() throws Exception {
        // 合并抛错也必须释放租约（try-with-resources），否则 15s TTL 内同沙箱写入全部 busy
        locatedAt("sbx-1");
        when(envFile.discoverEnvPath(handle)).thenReturn(ENV_PATH);
        when(envFile.mergeAndWrite(any(), anyString(), anyMap()))
            .thenThrow(new SandboxEnvException("sandbox_unavailable", 502, "mv failed"));

        mvc.perform(put("/sandbox/env").contentType(MediaType.APPLICATION_JSON).content("""
            {"userId":"u-1","sessionId":"sess-1","env":{"A":"1"}}
            """))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error").value("sandbox_unavailable"));

        verify(lease).close();
    }
}

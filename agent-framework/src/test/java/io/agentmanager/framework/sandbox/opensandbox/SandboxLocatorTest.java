package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxApiException;

import io.agentmanager.framework.config.SandboxConfig;
import io.agentmanager.framework.model.OafConfig;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;

/**
 * {@link SandboxLocator} 单元测试：候选隔离键按配置的 IsolationScope 固定生成
 * （USER → USER(sessionId) → USER(userId)；SESSION → SESSION(sessionId)；AGENT/GLOBAL 拒绝）、
 * 状态缺失/connect 失败的降级与 404 语义。
 */
class SandboxLocatorTest {

    private static final String USER = "u-1";
    private static final String SESSION = "sess-1";
    private static final String AGENT = "test-agent";

    private SessionSandboxStateStore stateStore;
    private SandboxLocator locator;

    @BeforeEach
    void setUp() {
        stateStore = mock(SessionSandboxStateStore.class);
        OafConfig oafConfig = new OafConfig(AGENT, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null);
        SandboxConfig config = new SandboxConfig(false, "img", 60, 30, 1024, 1, List.of("/e"),
            Duration.ofMillis(100), true, true, 900, true, true, true, false, 500, 30,
            new SandboxConfig.OpenSandboxConfig("server:8090", "key"));
        locator = new SandboxLocator(stateStore, oafConfig, config, null, null);
    }

    /** 打桩 SDK connector：connect() 返回指定句柄（多个则按调用顺序排队） */
    private MockedStatic<Sandbox> connectReturns(List<Sandbox> results) {
        MockedStatic<Sandbox> sdk = mockStatic(Sandbox.class);
        Sandbox.Connector connector = mock(Sandbox.Connector.class);
        sdk.when(Sandbox::connector).thenReturn(connector);
        when(connector.sandboxId(anyString())).thenReturn(connector);
        when(connector.connectionConfig(any(ConnectionConfig.class))).thenReturn(connector);
        when(connector.skipHealthCheck(anyBoolean())).thenReturn(connector);
        if (results.size() == 1) {
            when(connector.connect()).thenReturn(results.get(0));
        } else {
            when(connector.connect()).thenReturn(results.get(0),
                results.subList(1, results.size()).toArray(new Sandbox[0]));
        }
        return sdk;
    }

    private static String stateJson(String sandboxId) {
        return "{\"type\":\"opensandbox\",\"sandboxId\":\"" + sandboxId + "\"}";
    }

    private static Sandbox handle() {
        return mock(Sandbox.class);
    }

    // ---------- 候选键生成 ----------

    @Test
    void userScopeShouldTrySessionIdCandidateFirst() throws Exception {
        // 第一个候选（USER(sessionId)）命中
        when(stateStore.load(any())).thenReturn(Optional.of(stateJson("sbx-1")));
        MockedStatic<Sandbox> sdk = connectReturns(List.of(handle()));
        try {
            LocatedSandbox located = locator.locate(USER, SESSION, IsolationScope.USER);
            assertEquals("sbx-1", located.sandboxId());
            ArgumentCaptor<SandboxIsolationKey> key = ArgumentCaptor.forClass(SandboxIsolationKey.class);
            verify(stateStore).load(key.capture());
            assertEquals(IsolationScope.USER, key.getValue().getScope());
            assertEquals(SESSION, key.getValue().getValue(), "Channel 链路 ctx.userId=前端 sid → USER(sessionId)");
        } finally {
            sdk.close();
        }
    }

    @Test
    void userScopeShouldFallBackToUserIdCandidate() throws Exception {
        // 第一个候选（USER(sessionId)）无状态 → 第二个候选 USER(userId) 命中
        when(stateStore.load(any())).thenReturn(Optional.empty(), Optional.of(stateJson("sbx-2")));
        MockedStatic<Sandbox> sdk = connectReturns(List.of(handle()));
        try {
            LocatedSandbox located = locator.locate(USER, SESSION, IsolationScope.USER);
            assertEquals("sbx-2", located.sandboxId());
            ArgumentCaptor<SandboxIsolationKey> key = ArgumentCaptor.forClass(SandboxIsolationKey.class);
            verify(stateStore, org.mockito.Mockito.times(2)).load(key.capture());
            List<SandboxIsolationKey> tried = key.getAllValues();
            assertEquals(SESSION, tried.get(0).getValue());
            assertEquals(USER, tried.get(1).getValue());
        } finally {
            sdk.close();
        }
    }

    @Test
    void sessionScopeShouldUseSessionKey() throws Exception {
        when(stateStore.load(any())).thenReturn(Optional.of(stateJson("sbx-3")));
        MockedStatic<Sandbox> sdk = connectReturns(List.of(handle()));
        try {
            locator.locate(USER, SESSION, IsolationScope.SESSION);
            ArgumentCaptor<SandboxIsolationKey> key = ArgumentCaptor.forClass(SandboxIsolationKey.class);
            verify(stateStore).load(key.capture());
            assertEquals(IsolationScope.SESSION, key.getValue().getScope());
            assertEquals(SESSION, key.getValue().getValue());
        } finally {
            sdk.close();
        }
    }

    @Test
    void agentScopeShouldBeRejected() {
        SandboxEnvException e = assertThrows(SandboxEnvException.class,
            () -> locator.locate(USER, SESSION, IsolationScope.AGENT));
        assertEquals("unsupported_isolation_scope", e.getErrorCode());
        assertEquals(409, e.getHttpStatus());
    }

    @Test
    void globalScopeShouldBeRejected() {
        SandboxEnvException e = assertThrows(SandboxEnvException.class,
            () -> locator.locate(USER, SESSION, IsolationScope.GLOBAL));
        assertEquals("unsupported_isolation_scope", e.getErrorCode());
    }

    // ---------- 失败语义 ----------

    @Test
    void shouldReturn404WhenAllCandidatesMissing() throws Exception {
        when(stateStore.load(any())).thenReturn(Optional.empty());
        MockedStatic<Sandbox> sdk = connectReturns(List.of(handle()));
        try {
            SandboxEnvException e = assertThrows(SandboxEnvException.class,
                () -> locator.locate(USER, SESSION, IsolationScope.USER));
            assertEquals("sandbox_not_found", e.getErrorCode());
            assertEquals(404, e.getHttpStatus());
        } finally {
            sdk.close();
        }
    }

    @Test
    void connectFailureShouldFallThroughToNextCandidate() throws Exception {
        // 第一个候选状态存在但容器已销毁（connect 404）→ 第二个候选命中
        when(stateStore.load(any()))
            .thenReturn(Optional.of(stateJson("sbx-dead")))
            .thenReturn(Optional.of(stateJson("sbx-live")));
        MockedStatic<Sandbox> sdk = mockStatic(Sandbox.class);
        Sandbox.Connector connector = mock(Sandbox.Connector.class);
        Sandbox handle = mock(Sandbox.class);
        sdk.when(Sandbox::connector).thenReturn(connector);
        when(connector.sandboxId(anyString())).thenReturn(connector);
        when(connector.connectionConfig(any(ConnectionConfig.class))).thenReturn(connector);
        when(connector.skipHealthCheck(anyBoolean())).thenReturn(connector);
        SandboxApiException gone = mock(SandboxApiException.class);
        when(gone.getStatusCode()).thenReturn(404);
        when(connector.connect()).thenThrow(gone).thenReturn(handle);
        try {
            LocatedSandbox located = locator.locate(USER, SESSION, IsolationScope.USER);
            assertEquals("sbx-live", located.sandboxId());
        } finally {
            sdk.close();
        }
    }

    @Test
    void stateLoadIoErrorShouldReturn500() throws Exception {
        when(stateStore.load(any())).thenThrow(new IOException("db down"));
        SandboxEnvException e = assertThrows(SandboxEnvException.class,
            () -> locator.locate(USER, SESSION, IsolationScope.USER));
        assertEquals("state_unavailable", e.getErrorCode());
        assertEquals(500, e.getHttpStatus());
    }

    @Test
    void stateWithoutSandboxIdShouldBeSkipped() throws Exception {
        // 状态存在但缺 sandboxId → 跳过该候选；第二候选命中
        when(stateStore.load(any()))
            .thenReturn(Optional.of("{\"type\":\"opensandbox\"}"))
            .thenReturn(Optional.of(stateJson("sbx-2")));
        MockedStatic<Sandbox> sdk = connectReturns(List.of(handle()));
        try {
            assertEquals("sbx-2", locator.locate(USER, SESSION, IsolationScope.USER).sandboxId());
        } finally {
            sdk.close();
        }
    }

    @Test
    void constructorShouldNotFailWhenApiKeyBlank() {
        // 回归（E2E 工具插件冒烟）：沙箱未启用的部署没有 OPENSANDBOX_API_KEY，
        // SDK ConnectionConfig.Builder 拒绝空 apiKey——构造器急切构建会导致 Bean 创建失败、
        // 整服务起不来。惰性构建后构造必须成功，仅在真正 locate() 连接时才校验。
        OafConfig oafConfig = new OafConfig(AGENT, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null);
        SandboxConfig config = new SandboxConfig(false, "img", 60, 30, 1024, 1, List.of("/e"),
            Duration.ofMillis(100), true, true, 900, true, true, true, false, 500, 30,
            new SandboxConfig.OpenSandboxConfig("server:8090", ""));
        assertDoesNotThrow(() -> new SandboxLocator(stateStore, oafConfig, config, null, null));
    }

    // ---------- 归属校验+占位（U4，终审 d1/cold-R1-1：locate 直连同 P3 规则） ----------

    /** 带 lifecycle 簿记的 locator（providers 注入 mock） */
    private SandboxLocator locateWithLifecycle(SandboxBindingStore store,
                                               SandboxLifecycleManager manager) {
        OafConfig oafConfig = new OafConfig(AGENT, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null);
        SandboxConfig config = new SandboxConfig(false, "img", 60, 30, 1024, 1, List.of("/e"),
            Duration.ofMillis(100), true, true, 900, true, true, true, false, 500, 30,
            new SandboxConfig.OpenSandboxConfig("server:8090", "key"));
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<SandboxBindingStore> storeProvider =
            mock(org.springframework.beans.factory.ObjectProvider.class);
        when(storeProvider.getIfAvailable()).thenReturn(store);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<SandboxLifecycleManager> lifecycleProvider =
            mock(org.springframework.beans.factory.ObjectProvider.class);
        when(lifecycleProvider.getIfAvailable()).thenReturn(manager);
        return new SandboxLocator(stateStore, oafConfig, config, storeProvider, lifecycleProvider);
    }

    @Test
    void locateOwnershipOkReturnsSandbox() throws Exception {
        // 归属校验+占位 affected=1 → 放行
        var store = mock(SandboxBindingStore.class);
        var manager = mock(SandboxLifecycleManager.class);
        when(manager.bindingStore()).thenReturn(store);
        when(store.occupyWrite(anyString(), anyList())).thenReturn(true);
        when(stateStore.load(any())).thenReturn(Optional.of(stateJson("sbx-own")));
        var locator = locateWithLifecycle(store, manager);
        MockedStatic<Sandbox> sdk = connectReturns(List.of(handle()));
        try {
            assertEquals("sbx-own", locator.locate(USER, SESSION, IsolationScope.USER).sandboxId());
            verify(store).occupyWrite("sbx-own", List.of(SESSION, USER));
        } finally {
            sdk.close();
        }
    }

    @Test
    void locateOwnershipLostSkipsCandidateAndDegrades() throws Exception {
        // affected=0（无行/已改绑/漂移）→ close 句柄 + 跳过候选（U4 三分支之二）
        var store = mock(SandboxBindingStore.class);
        var manager = mock(SandboxLifecycleManager.class);
        when(manager.bindingStore()).thenReturn(store);
        when(store.occupyWrite(anyString(), anyList())).thenReturn(false);
        when(stateStore.load(any())).thenReturn(Optional.of(stateJson("sbx-stolen")));
        var locator = locateWithLifecycle(store, manager);
        var handle = handle();
        MockedStatic<Sandbox> sdk = connectReturns(List.of(handle));
        try {
            assertThrows(SandboxEnvException.class,
                () -> locator.locate(USER, SESSION, IsolationScope.USER));
            // USER 范围两个候选（sessionId/userId）都读到同一状态、都校验失败 → close ×2
            verify(handle, org.mockito.Mockito.times(2)).close();
        } finally {
            sdk.close();
        }
    }

    @Test
    void locateOwnershipCheckFailsOpen() throws Exception {
        // 校验写异常（MySQL 不可达）→ 放行 + WARN（fail-open 同纪律，U4 三分支之三）
        var store = mock(SandboxBindingStore.class);
        var manager = mock(SandboxLifecycleManager.class);
        when(manager.bindingStore()).thenReturn(store);
        when(store.occupyWrite(anyString(), anyList()))
            .thenThrow(new SandboxBindingStore.DataAccessException("db down", null));
        when(stateStore.load(any())).thenReturn(Optional.of(stateJson("sbx-own")));
        var locator = locateWithLifecycle(store, manager);
        MockedStatic<Sandbox> sdk = connectReturns(List.of(handle()));
        try {
            assertEquals("sbx-own", locator.locate(USER, SESSION, IsolationScope.USER).sandboxId());
        } finally {
            sdk.close();
        }
    }
}

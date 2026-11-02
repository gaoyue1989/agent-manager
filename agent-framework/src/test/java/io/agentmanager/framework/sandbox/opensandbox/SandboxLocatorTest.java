package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
        SandboxConfig config = new SandboxConfig(false, "img", 60, 1024, 1, List.of("/e"),
            Duration.ofMillis(100), true, true, 900, true, true, true, false, 500, 30,
            new SandboxConfig.OpenSandboxConfig("server:8090", "key"));
        locator = new SandboxLocator(stateStore, oafConfig, config);
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
}
